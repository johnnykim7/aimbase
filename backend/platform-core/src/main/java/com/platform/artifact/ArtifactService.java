package com.platform.artifact;

import com.platform.config.WorkspaceProperties;
import com.platform.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CR-132: CLI 산출물(pptx/xlsx/pdf/docx …) 다운로드 다리.
 *
 * <p>파일 생성 능력은 Claude CLI 가 이미 가진다(Bash + python-pptx/openpyxl/reportlab/python-docx).
 * aimbase 는 도구를 새로 만들지 않고 ① 러너에 런타임을 깔아주고 ② 만든 파일을 위젯이 내려받게 다리를 놓는다.
 *
 * <h3>산출물 폴더 규약</h3>
 * CLI 는 세션 workspace(cwd) 하위 {@code out/} 에 산출물을 쓴다(system_prompt_override 로 규약 주입).
 * 이 서비스는 {@code {workspaceRef}/out/} 만 스캔·서빙한다.
 *
 * <h3>격리 (BIZ-003)</h3>
 * <ul>
 *   <li>세션→workspace 매핑은 {@link SessionStore#getWorkspaceRef}로 <b>현재 테넌트 DB</b>에서만 조회된다.
 *       타 테넌트 sessionId 는 workspaceRef=null 이 되어 자연히 차단된다.</li>
 *   <li>파일명 경로탈출({@code ../}) 은 normalize 후 out/ 디렉토리 startsWith 로 차단.</li>
 *   <li>whitelist-roots 안에 있는 workspace 만 허용({@link WorkspaceProperties#isInsideWhitelist}).</li>
 * </ul>
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** CLI 가 산출물을 쓰는 workspace 하위 고정 폴더명. */
    static final String OUT_DIR = "out";

    /** 위젯에 노출하는 산출물 확장자 화이트리스트(중간 임시파일/입력첨부 노이즈 배제). */
    private static final Set<String> ARTIFACT_EXTS = Set.of(
            "pptx", "xlsx", "pdf", "docx", "csv", "txt", "md", "json", "png", "html");

    private final SessionStore sessionStore;
    private final WorkspaceProperties workspaceProperties;

    public ArtifactService(SessionStore sessionStore, WorkspaceProperties workspaceProperties) {
        this.sessionStore = sessionStore;
        this.workspaceProperties = workspaceProperties;
    }

    /** 산출물 메타(위젯 done 페이로드 + 목록 응답용). */
    public record ArtifactInfo(String name, long size) {}

    /**
     * 세션의 {@code {workspace}/out/} 를 스캔해 산출물 목록을 반환. workspace 미설정/폴더 없음이면 빈 목록.
     * 최신 수정순 정렬.
     */
    public List<ArtifactInfo> listArtifacts(String sessionId) {
        Path outDir = resolveOutDir(sessionId);
        if (outDir == null || !Files.isDirectory(outDir)) {
            return List.of();
        }
        try (var stream = Files.list(outDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> hasArtifactExt(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(ArtifactService::lastModifiedSafe).reversed())
                    .toList();
            List<ArtifactInfo> out = new ArrayList<>(files.size());
            for (Path p : files) {
                out.add(new ArtifactInfo(p.getFileName().toString(), sizeSafe(p)));
            }
            return out;
        } catch (IOException e) {
            log.warn("[CR132] out/ 스캔 실패 session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 다운로드용 실제 파일 경로 해석. 격리·경로탈출·존재 검증을 모두 통과해야 반환.
     *
     * @param fileName 순수 파일명(경로 구분자·상위참조 금지)
     * @return 검증된 정규 파일 경로
     * @throws ArtifactNotFoundException workspace 미설정, 폴더/파일 없음, 확장자 불허
     * @throws ArtifactAccessException   경로탈출 시도, whitelist 밖
     */
    public Path resolveDownloadPath(String sessionId, String fileName) {
        Path outDir = resolveOutDir(sessionId);
        if (outDir == null) {
            throw new ArtifactNotFoundException("세션에 연결된 산출물 폴더 없음: " + sessionId);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ArtifactNotFoundException("파일명 누락");
        }
        if (!hasArtifactExt(fileName)) {
            throw new ArtifactNotFoundException("허용되지 않은 산출물 형식: " + fileName);
        }
        // 경로탈출 차단: out/ 아래로 정규화한 뒤 여전히 out/ 내부인지 확인.
        Path target = outDir.resolve(fileName).normalize();
        if (!target.startsWith(outDir)) {
            throw new ArtifactAccessException("경로 탈출 차단: " + fileName);
        }
        if (!Files.isRegularFile(target)) {
            throw new ArtifactNotFoundException("산출물 없음: " + fileName);
        }
        return target;
    }

    /** 세션 workspace/out/ 절대경로. whitelist 밖이거나 workspace 미설정이면 null/예외. */
    private Path resolveOutDir(String sessionId) {
        String workspaceRef = sessionStore.getWorkspaceRef(sessionId);
        if (workspaceRef == null || workspaceRef.isBlank()) {
            return null;
        }
        Path workspace = Path.of(workspaceRef).toAbsolutePath().normalize();
        if (!workspaceProperties.isInsideWhitelist(workspace)) {
            throw new ArtifactAccessException("workspace 화이트리스트 밖: " + workspace);
        }
        return workspace.resolve(OUT_DIR).normalize();
    }

    private static boolean hasArtifactExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return ARTIFACT_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static long lastModifiedSafe(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long sizeSafe(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 산출물 없음 → 404. */
    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(String message) { super(message); }
    }

    /** 격리/경로탈출 위반 → 403. */
    public static class ArtifactAccessException extends RuntimeException {
        public ArtifactAccessException(String message) { super(message); }
    }
}
