package com.platform.skill;

import com.platform.domain.SkillBundleBase;
import com.platform.domain.SkillBundleFileBase;
import com.platform.domain.SkillBundleFileInfo;
import com.platform.domain.SkillBundleVersionBase;
import com.platform.policy.AuditLogger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CR-147: 스킬 묶음 올리기와 조회 (PRD-341). 테넌트 전용과 플랫폼 공용 API 가 같이 쓴다.
 */
@Service
public class SkillBundleService {

    private final SkillBundleArchive archive;
    private final SkillCatalog catalog;
    private final SkillBundleSettings settings;
    private final AuditLogger auditLogger;

    public SkillBundleService(SkillBundleArchive archive, SkillCatalog catalog,
                              SkillBundleSettings settings, AuditLogger auditLogger) {
        this.archive = archive;
        this.catalog = catalog;
        this.settings = settings;
        this.auditLogger = auditLogger;
    }

    public UploadOutcome upload(SkillScope scope, MultipartFile file, String userId) {
        if (file == null || file.isEmpty()) {
            throw new SkillBundleRejectedException("올린 파일이 없습니다");
        }
        long max = settings.bundleMaxBytes();
        if (file.getSize() > max) {
            throw new SkillBundleRejectedException("zip 크기 " + file.getSize() + " bytes 가 상한 " + max + " bytes 를 넘습니다");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new SkillBundleRejectedException("올린 파일을 읽을 수 없습니다: " + e.getMessage());
        }
        ParsedSkillBundle parsed = archive.parse(bytes);
        UploadOutcome outcome;
        try {
            outcome = catalog.store(scope).save(parsed, userId);
        } catch (DataIntegrityViolationException e) {
            // 같은 이름 첫 판을 두 곳에서 동시에 올리면 한쪽이 이름 UNIQUE 에 걸린다
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "같은 이름의 스킬 묶음이 동시에 올라갔습니다. 다시 올려 주세요: " + parsed.name());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scope", scope.value());
        detail.put("version", outcome.version());
        detail.put("created", outcome.created());
        detail.put("reactivated", outcome.reactivated());
        detail.put("file_count", parsed.files().size());
        detail.put("total_bytes", parsed.totalBytes());
        auditLogger.log("skill_bundle_upload", outcome.name(), userId, null, detail, null);
        return outcome;
    }

    public void setActive(SkillScope scope, String name, boolean active, String userId) {
        catalog.store(scope).setActive(name, active);
        auditLogger.log(active ? "skill_bundle_activate" : "skill_bundle_deactivate", name, userId, null,
                Map.of("scope", scope.value()), null);
    }

    /** 테넌트 화면 목록. 테넌트 전용과 플랫폼 공용을 모두 싣고, 활성 테넌트 전용에 가려진 공용은 shadowed. */
    public List<Map<String, Object>> listForTenant() {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> activeTenantNames = new HashSet<>();
        for (SkillBundleBase b : catalog.store(SkillScope.TENANT).listBundles()) {
            items.add(bundleSummary(b, SkillScope.TENANT, false));
            if (b.isActive()) activeTenantNames.add(b.getName());
        }
        for (SkillBundleBase b : catalog.store(SkillScope.PLATFORM).listBundles()) {
            items.add(bundleSummary(b, SkillScope.PLATFORM, activeTenantNames.contains(b.getName())));
        }
        items.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("name"))
                .thenComparing(m -> (String) m.get("scope")));
        return items;
    }

    public List<Map<String, Object>> listForPlatform() {
        return catalog.store(SkillScope.PLATFORM).listBundles().stream()
                .map(b -> bundleSummary(b, SkillScope.PLATFORM, false))
                .toList();
    }

    public Map<String, Object> getBundle(String name, SkillScope scope) {
        return bundleDetail(locate(name, scope), false);
    }

    /** 테넌트 화면 상세. 목록과 같은 기준으로 활성 테넌트 전용에 가려진 공용은 shadowed. */
    public Map<String, Object> getBundleForTenant(String name, SkillScope scope) {
        Located located = locate(name, scope);
        boolean shadowed = located.store().scope() == SkillScope.PLATFORM
                && catalog.store(SkillScope.TENANT).findBundle(located.bundle().getName())
                        .filter(SkillBundleBase::isActive).isPresent();
        return bundleDetail(located, shadowed);
    }

    private Map<String, Object> bundleDetail(Located located, boolean shadowed) {
        Map<String, Object> result = bundleSummary(located.bundle(), located.store().scope(), shadowed);
        result.put("versions", located.store().listVersions(located.bundle().getId()).stream()
                .map(this::versionSummary).toList());
        return result;
    }

    public Map<String, Object> getVersion(String name, int version, SkillScope scope) {
        Located located = locate(name, scope);
        SkillBundleVersionBase v = requireVersion(located, version);
        Map<String, Object> result = versionSummary(v);
        result.put("name", located.bundle().getName());
        result.put("scope", located.store().scope().value());
        result.put("frontmatter", v.getFrontmatter());
        result.put("instructions", v.getInstructions());
        List<Map<String, Object>> files = new ArrayList<>();
        for (SkillBundleFileInfo f : located.store().listFiles(v.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path());
            m.put("size_bytes", f.sizeBytes());
            m.put("is_text", f.isText());
            files.add(m);
        }
        result.put("files", files);
        return result;
    }

    /** 텍스트면 skills.read-max-bytes 까지 내용을 싣고, 아니면 크기만. */
    public Map<String, Object> getFile(String name, int version, String path, SkillScope scope) {
        Located located = locate(name, scope);
        SkillBundleVersionBase v = requireVersion(located, version);
        SkillBundleFileBase file = located.store().findFile(v.getId(), path)
                .orElseThrow(() -> new SkillNotFoundException("스킬 " + name + " 판 " + version + " 에 파일이 없습니다: " + path));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.getPath());
        result.put("size_bytes", file.getSizeBytes());
        result.put("is_text", file.isText());
        if (file.isText()) {
            int max = settings.readMaxBytes();
            byte[] content = file.getContent();
            boolean truncated = content.length > max;
            result.put("content", truncated
                    ? Utf8.truncate(content, max)
                    : new String(content, StandardCharsets.UTF_8));
            result.put("truncated", truncated);
        }
        return result;
    }

    private record Located(SkillBundleStore<?, ?, ?> store, SkillBundleBase bundle) {}

    /** scope 가 없으면 이름 해석(BIZ-120), 활성 묶음이 없으면 비활성이라도 테넌트 전용, 그다음 플랫폼 공용. */
    private Located locate(String name, SkillScope scope) {
        if (scope != null) {
            SkillBundleStore<?, ?, ?> store = catalog.store(scope);
            return store.findBundle(name).map(b -> new Located(store, b))
                    .orElseThrow(() -> new SkillNotFoundException("스킬 묶음이 없습니다: " + name));
        }
        for (SkillScope s : List.of(SkillScope.TENANT, SkillScope.PLATFORM)) {
            SkillBundleStore<?, ?, ?> store = catalog.store(s);
            Optional<SkillBundleBase> b = store.findBundle(name).filter(SkillBundleBase::isActive);
            if (b.isPresent()) return new Located(store, b.get());
        }
        for (SkillScope s : List.of(SkillScope.TENANT, SkillScope.PLATFORM)) {
            SkillBundleStore<?, ?, ?> store = catalog.store(s);
            Optional<SkillBundleBase> b = store.findBundle(name);
            if (b.isPresent()) return new Located(store, b.get());
        }
        throw new SkillNotFoundException("스킬 묶음이 없습니다: " + name);
    }

    private static SkillBundleVersionBase requireVersion(Located located, int version) {
        return located.store().findVersion(located.bundle().getId(), version)
                .orElseThrow(() -> new SkillNotFoundException(
                        "BIZ-120: 스킬 " + located.bundle().getName() + " 에 판 " + version + " 이 없습니다"));
    }

    private Map<String, Object> bundleSummary(SkillBundleBase b, SkillScope scope, boolean shadowed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.getName());
        m.put("description", b.getDescription());
        m.put("scope", scope.value());
        m.put("latest_version", b.getLatestVersion());
        m.put("is_active", b.isActive());
        m.put("shadowed", shadowed);
        m.put("created_at", b.getCreatedAt());
        m.put("updated_at", b.getUpdatedAt());
        return m;
    }

    private Map<String, Object> versionSummary(SkillBundleVersionBase v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.getVersion());
        m.put("description", v.getDescription());
        m.put("file_count", v.getFileCount());
        m.put("total_bytes", v.getTotalBytes());
        m.put("content_sha256", v.getContentSha256());
        m.put("warnings", v.getWarnings());
        m.put("uploaded_by", v.getUploadedBy());
        m.put("created_at", v.getCreatedAt());
        return m;
    }
}
