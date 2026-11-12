package com.platform.chatdoc;

import com.platform.artifact.ArtifactService;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.repository.ChatDocumentRepository;
import com.platform.repository.ChatDocumentVersionRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CR-181: 옆 창 문서와 판의 저장과 조회(BIZ-200, BIZ-202).
 * FILE 판은 저장소(StorageService)에 사본을 두고, 작업 폴더 out/ 의 파일은 고칠 때마다 판 내용으로 맞춘다.
 */
@Service
public class ChatDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ChatDocumentService.class);

    static final String STORAGE_CATEGORY = "chat-documents";
    /** CR-182 create_file 이 도구 입력을 남기는 폴더. out/ 아래에 있다. */
    static final String SOURCE_DIR = ".source";
    static final String SOURCE_SUFFIX = ".json";

    private final ChatDocumentRepository documents;
    private final ChatDocumentVersionRepository versions;
    private final StorageService storage;
    private final ArtifactService artifactService;

    public ChatDocumentService(ChatDocumentRepository documents,
                               ChatDocumentVersionRepository versions,
                               StorageService storage,
                               ArtifactService artifactService) {
        this.documents = documents;
        this.versions = versions;
        this.storage = storage;
        this.artifactService = artifactService;
    }

    /** 저장소에 둔 FILE 판 한 벌. 원본이 없는 파일은 sourceStoragePath 가 null. */
    public record StoredFile(String storagePath, String sourceStoragePath, long sizeBytes, String sha256) {}

    public Optional<ChatDocumentEntity> find(UUID documentId) {
        return documents.findById(documentId);
    }

    public Optional<ChatDocumentEntity> findFile(String sessionId, String name) {
        return documents.findFile(sessionId, name);
    }

    public Optional<ChatDocumentEntity> findBlock(String sessionId, UUID turnId, int index) {
        return documents.findBlock(sessionId, turnId, index);
    }

    public Optional<ChatDocumentVersionEntity> liveVersion(UUID documentId, int version) {
        return versions.findLive(documentId, version);
    }

    /** CR-180 미리보기의 판 인자. 파일 문서가 없거나 그 판이 지워졌으면 비어 있다. */
    public Optional<ChatDocumentVersionEntity> liveFileVersion(String sessionId, String name, int version) {
        return documents.findFile(sessionId, name).flatMap(d -> versions.findLive(d.getId(), version));
    }

    /** FILE 판 파일을 target 에 꺼낸다. 미리보기 변환과 판 내려받기가 쓴다. */
    public void copyVersionTo(ChatDocumentVersionEntity v, Path target) throws IOException {
        copyFromStorage(v.getStoragePath(), target);
    }

    /** 처음 고치는 코드 블록. 위젯이 보낸 블록 원문이 1판이다. */
    @Transactional
    public ChatDocumentEntity createBlock(String sessionId, UUID turnId, int index, String lang, String source) {
        ChatDocumentEntity d = newDocument(sessionId, ChatDocumentEntity.Kind.BLOCK);
        d.setOriginTurnId(turnId);
        d.setBlockIndex(index);
        d.setLang(lang);
        documents.saveAndFlush(d);
        versions.save(blockVersion(d.getId(), 1, null, ChatDocumentVersionEntity.MadeBy.ORIGINAL, turnId, source));
        return d;
    }

    /** 처음 고치는 파일. 그때 out/ 의 내용이 1판이다. */
    @Transactional
    public ChatDocumentEntity createFile(String sessionId, String name, StoredFile original) {
        ChatDocumentEntity d = newDocument(sessionId, ChatDocumentEntity.Kind.FILE);
        d.setName(name);
        documents.saveAndFlush(d);
        versions.save(fileVersion(d.getId(), 1, null, ChatDocumentVersionEntity.MadeBy.ORIGINAL, null, original));
        return d;
    }

    /** 고친 결과를 마지막 판 다음 번호로 더한다. 바탕 판이 마지막 판이 아니어도 그렇다(BIZ-200). */
    @Transactional
    public ChatDocumentVersionEntity addBlockVersion(UUID documentId, int baseVersion, UUID turnId, String content) {
        ChatDocumentEntity d = documents.findForUpdate(documentId).orElseThrow();
        int next = versions.findLiveMaxVersion(documentId) + 1;
        ChatDocumentVersionEntity v = versions.save(
                blockVersion(documentId, next, baseVersion, ChatDocumentVersionEntity.MadeBy.CHAT_EDIT, turnId, content));
        d.setLatestVersion(next);
        documents.save(d);
        return v;
    }

    @Transactional
    public ChatDocumentVersionEntity addFileVersion(UUID documentId, int baseVersion, UUID turnId, StoredFile file) {
        ChatDocumentEntity d = documents.findForUpdate(documentId).orElseThrow();
        int next = versions.findLiveMaxVersion(documentId) + 1;
        ChatDocumentVersionEntity v = versions.save(
                fileVersion(documentId, next, baseVersion, ChatDocumentVersionEntity.MadeBy.CHAT_EDIT, turnId, file));
        d.setLatestVersion(next);
        documents.save(d);
        return v;
    }

    /**
     * BIZ-204: 그 판 내용을 마지막 판 다음 번호로 복사한다(made_by RESTORE, 답 번호 없음).
     * FILE 은 그 판의 저장소 파일을 함께 가리킨다. 판 파일은 지우지 않으므로 따로 복사하지 않는다.
     *
     * @return 만든 판. 마지막 판과 내용이 같으면 null(BIZ-201)
     */
    @Transactional
    public ChatDocumentVersionEntity restoreVersion(UUID documentId, int version) {
        ChatDocumentEntity d = documents.findForUpdate(documentId).orElseThrow();
        ChatDocumentVersionEntity from = versions.findLive(documentId, version).orElseThrow();
        ChatDocumentVersionEntity latest = versions.findLive(documentId, d.getLatestVersion()).orElse(null);
        if (latest != null && latest.getSha256().equals(from.getSha256())) return null;
        int next = versions.findLiveMaxVersion(documentId) + 1;
        ChatDocumentVersionEntity.MadeBy by = ChatDocumentVersionEntity.MadeBy.RESTORE;
        ChatDocumentVersionEntity v = d.getKind() == ChatDocumentEntity.Kind.BLOCK
                ? blockVersion(documentId, next, version, by, null, from.getContent())
                : fileVersion(documentId, next, version, by, null, new StoredFile(
                        from.getStoragePath(), from.getSourceStoragePath(), from.getSizeBytes(), from.getSha256()));
        v = versions.save(v);
        d.setLatestVersion(next);
        documents.save(d);
        return v;
    }

    /**
     * BIZ-202: 되감기로 지운 답이 만든 판에 같은 시각으로 지운 표시를 하고 마지막 판 번호를 다시 센다.
     * 대화 메시지의 지운 표시와 같은 트랜잭션에서 불린다.
     *
     * @return 판이 지워진 문서
     */
    @Transactional
    public Set<UUID> deleteEditsOfTurns(Collection<UUID> turnIds, OffsetDateTime ts) {
        if (turnIds == null || turnIds.isEmpty()) return Set.of();
        List<ChatDocumentVersionEntity> hit = versions.findLiveEditsByTurnIds(turnIds);
        if (hit.isEmpty()) return Set.of();
        versions.softDelete(hit.stream().map(ChatDocumentVersionEntity::getId).toList(), ts);
        Set<UUID> docIds = hit.stream().map(ChatDocumentVersionEntity::getDocumentId).collect(Collectors.toSet());
        for (UUID docId : docIds) {
            documents.findForUpdate(docId).ifPresent(d -> {
                d.setLatestVersion(Math.max(1, versions.findLiveMaxVersion(docId)));
                documents.save(d);
            });
        }
        return docIds;
    }

    /** 되감기나 실패 뒤 FILE 문서의 out/ 파일과 원본을 남은 마지막 판 내용으로 되돌린다. */
    public void restoreFilesToLatest(Collection<UUID> documentIds) {
        for (UUID id : new HashSet<>(documentIds)) {
            documents.findById(id).ifPresent(this::restoreLatest);
        }
    }

    public void restoreLatest(ChatDocumentEntity d) {
        if (d.getKind() != ChatDocumentEntity.Kind.FILE) return;
        versions.findLive(d.getId(), d.getLatestVersion()).ifPresent(v -> {
            try {
                writeToOutDir(d, v);
            } catch (IOException | RuntimeException e) {
                log.warn("[CR181] out/ 되돌리기 실패 doc={} v={}: {}", d.getId(), v.getVersion(), e.getMessage());
            }
        });
    }

    /**
     * out/<이름> 과 원본을 그 판 내용으로 맞춘다. 파일이 이미 같은 내용이면 쓰지 않는다.
     * 판에 원본이 없는데 out/.source 에 원본이 남아 있으면 지운다. 고치기 방식이 판 내용과 어긋나지 않게 하려는 것이다.
     */
    public void writeToOutDir(ChatDocumentEntity d, ChatDocumentVersionEntity v) throws IOException {
        Path file = outFile(d.getSessionId(), d.getName());
        if (file == null) throw new IOException("세션 작업 폴더 없음: " + d.getSessionId());
        if (!Files.isRegularFile(file) || !sha256(file).equals(v.getSha256())) {
            copyFromStorage(v.getStoragePath(), file);
        }
        Path source = sourceFile(d.getSessionId(), d.getName());
        if (v.getSourceStoragePath() != null) {
            copyFromStorage(v.getSourceStoragePath(), source);
        } else if (source != null) {
            Files.deleteIfExists(source);
        }
    }

    /** 파일과 원본(있으면)을 저장소에 둔다. */
    public StoredFile storeFile(String name, Path file, Path source) throws IOException {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new IllegalStateException("tenant context 없음");
        String sha = sha256(file);
        long size = Files.size(file);
        String path;
        try (InputStream in = Files.newInputStream(file)) {
            path = storage.save(tenantId, STORAGE_CATEGORY, name, in);
        }
        String sourcePath = null;
        if (source != null && Files.isRegularFile(source)) {
            try (InputStream in = Files.newInputStream(source)) {
                sourcePath = storage.save(tenantId, STORAGE_CATEGORY, name + SOURCE_SUFFIX, in);
            }
        }
        return new StoredFile(path, sourcePath, size, sha);
    }

    public InputStream openFile(ChatDocumentVersionEntity v) {
        return storage.load(v.getStoragePath());
    }

    public String readSource(ChatDocumentVersionEntity v) {
        if (v.getSourceStoragePath() == null) return null;
        try (InputStream in = storage.load(v.getSourceStoragePath())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 세션 작업 폴더의 out/<이름>. 작업 폴더가 없으면 null. 이름은 DocumentEditService 가 검사한다. */
    public Path outFile(String sessionId, String name) {
        Path outDir = artifactService.resolveOutDir(sessionId);
        if (outDir == null) return null;
        Path target = outDir.resolve(name).normalize();
        if (!target.getParent().equals(outDir)) throw new IllegalArgumentException("경로 탈출: " + name);
        return target;
    }

    /** CR-182 원본 out/.source/<이름>.json. 작업 폴더가 없으면 null. */
    public Path sourceFile(String sessionId, String name) {
        Path outDir = artifactService.resolveOutDir(sessionId);
        if (outDir == null) return null;
        Path dir = outDir.resolve(SOURCE_DIR);
        Path target = dir.resolve(name + SOURCE_SUFFIX).normalize();
        if (!target.getParent().equals(dir)) throw new IllegalArgumentException("경로 탈출: " + name);
        return target;
    }

    /** GET /api/v1/chat/documents 본문. 지운 판은 빼고 준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForSession(String sessionId) {
        List<ChatDocumentEntity> docs = documents.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (docs.isEmpty()) return List.of();
        Map<UUID, List<ChatDocumentVersionEntity>> byDoc = versions
                .findLiveByDocumentIds(docs.stream().map(ChatDocumentEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ChatDocumentVersionEntity::getDocumentId));
        List<Map<String, Object>> out = new ArrayList<>(docs.size());
        for (ChatDocumentEntity d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", d.getId().toString());
            m.put("kind", d.getKind().name());
            m.put("name", d.getName());
            m.put("turn_id", d.getOriginTurnId() != null ? d.getOriginTurnId().toString() : null);
            m.put("index", d.getBlockIndex());
            m.put("lang", d.getLang());
            m.put("latest_version", d.getLatestVersion());
            List<Map<String, Object>> vs = new ArrayList<>();
            for (ChatDocumentVersionEntity v : byDoc.getOrDefault(d.getId(), List.of())) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("version", v.getVersion());
                vm.put("base_version", v.getBaseVersion());
                vm.put("made_by", v.getMadeBy().name());
                vm.put("turn_id", v.getTurnId() != null ? v.getTurnId().toString() : null);
                vm.put("size", v.getSizeBytes());
                vm.put("created_at", v.getCreatedAt());
                vs.add(vm);
            }
            m.put("versions", vs);
            out.add(m);
        }
        return out;
    }

    /** BLOCK 해시는 앞뒤 공백을 뺀 본문으로 센다. 모델이 끝 줄바꿈만 바꾼 것을 고친 것으로 보지 않게 한다. */
    static String blockSha256(String content) {
        return sha256((content != null ? content.strip() : "").getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyFromStorage(String storagePath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".cr181-", ".tmp");
        try (InputStream in = storage.load(storagePath)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static ChatDocumentEntity newDocument(String sessionId, ChatDocumentEntity.Kind kind) {
        ChatDocumentEntity d = new ChatDocumentEntity();
        d.setId(UUID.randomUUID());
        d.setSessionId(sessionId);
        d.setKind(kind);
        d.setLatestVersion(1);
        return d;
    }

    private static ChatDocumentVersionEntity blockVersion(UUID documentId, int version, Integer baseVersion,
                                                          ChatDocumentVersionEntity.MadeBy madeBy, UUID turnId,
                                                          String content) {
        ChatDocumentVersionEntity v = new ChatDocumentVersionEntity();
        v.setDocumentId(documentId);
        v.setVersion(version);
        v.setBaseVersion(baseVersion);
        v.setMadeBy(madeBy);
        v.setTurnId(turnId);
        v.setContent(content);
        v.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
        v.setSha256(blockSha256(content));
        return v;
    }

    private static ChatDocumentVersionEntity fileVersion(UUID documentId, int version, Integer baseVersion,
                                                         ChatDocumentVersionEntity.MadeBy madeBy, UUID turnId,
                                                         StoredFile file) {
        ChatDocumentVersionEntity v = new ChatDocumentVersionEntity();
        v.setDocumentId(documentId);
        v.setVersion(version);
        v.setBaseVersion(baseVersion);
        v.setMadeBy(madeBy);
        v.setTurnId(turnId);
        v.setStoragePath(file.storagePath());
        v.setSourceStoragePath(file.sourceStoragePath());
        v.setSizeBytes(file.sizeBytes());
        v.setSha256(file.sha256());
        return v;
    }
}
