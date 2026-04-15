package com.platform.tool.workspace;

import com.platform.tool.ToolContext;
import com.platform.tool.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CR-029: 워크스페이스 정책 평가 엔진.
 * path traversal 방어 + 확장자 제한 + 파일 크기 제한 + 바이너리 차단 + 시크릿 탐지.
 * CR-045: 시스템 화이트리스트 독립 방어선 추가 (L2).
 */
public class WorkspacePolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePolicyEngine.class);

    private final WorkspaceResolver workspaceResolver;
    private final List<Path> whitelistRoots;

    public WorkspacePolicyEngine(WorkspaceResolver workspaceResolver) {
        this(workspaceResolver, List.of());
    }

    /**
     * CR-045: 화이트리스트 주입 생성자 (L2 독립 방어선).
     * WorkspaceResolver와 같은 설정을 참조하되 검증 로직은 독립.
     *
     * @param whitelistRoots 비어있으면 기존 동작 유지 (하위 호환)
     */
    public WorkspacePolicyEngine(WorkspaceResolver workspaceResolver, List<Path> whitelistRoots) {
        this.workspaceResolver = workspaceResolver;
        this.whitelistRoots = whitelistRoots != null ? List.copyOf(whitelistRoots) : List.of();
    }

    /**
     * 파일 경로의 정책 준수 여부를 검증.
     *
     * @param ctx    도구 실행 컨텍스트
     * @param policy 적용할 정책
     * @param path   검증할 파일 경로 (절대 또는 상대)
     * @return 검증 결과
     */
    public ValidationResult validatePath(ToolContext ctx, WorkspacePolicy policy, String path) {
        Path workspaceRoot;
        try {
            workspaceRoot = workspaceResolver.getWorkspaceRoot(ctx).toRealPath();
        } catch (IOException e) {
            workspaceRoot = workspaceResolver.getWorkspaceRoot(ctx).normalize();
        }
        final Path target;
        {
            Path candidate = workspaceResolver.resolve(ctx, path);
            try {
                candidate = candidate.toRealPath();
            } catch (IOException ignored) {
                candidate = candidate.normalize();
            }
            target = candidate;
        }

        // CR-045: L2 독립 방어선 — 시스템 화이트리스트 최종 게이트.
        // resolver(L1)가 우회되거나 버그로 뚫려도 여기서 차단.
        // target은 이미 toRealPath()로 정규화됨. 화이트리스트 루트도 같은 기준으로 비교.
        if (!whitelistRoots.isEmpty()) {
            final Path targetNormalized = target.toAbsolutePath().normalize();
            boolean inside = whitelistRoots.stream().anyMatch(root -> {
                Path rootReal;
                try {
                    rootReal = root.toRealPath();
                } catch (IOException ignored) {
                    rootReal = root.toAbsolutePath().normalize();
                }
                return targetNormalized.startsWith(rootReal);
            });
            if (!inside) {
                return ValidationResult.fail(
                        "화이트리스트 루트 외부 경로 접근 금지: " + path, 90);
            }
        }

        // 1. Path traversal 방어
        if (!target.startsWith(workspaceRoot)) {
            // allowedRoots 체크
            boolean allowed = policy.allowedRoots().stream()
                    .anyMatch(root -> target.startsWith(Path.of(root)));
            if (!allowed) {
                return ValidationResult.fail(
                        "경로가 허용된 워크스페이스 범위를 벗어남: " + path, 100);
            }
        }

        // 2. Denied path 체크
        for (String denied : policy.deniedPaths()) {
            if (target.toString().contains(denied)) {
                return ValidationResult.fail(
                        "접근이 거부된 경로: " + denied, 101);
            }
        }

        // 3. 확장자 체크
        String ext = getExtension(target);
        if (!policy.allowedExtensions().isEmpty() && !policy.allowedExtensions().contains(ext)) {
            return ValidationResult.fail(
                    "허용되지 않은 확장자: " + ext, 102);
        }
        if (policy.deniedExtensions().contains(ext)) {
            return ValidationResult.fail(
                    "거부된 확장자: " + ext, 103);
        }

        return ValidationResult.OK;
    }

    /**
     * 파일 내용의 정책 준수 여부를 검증 (읽기 후 체크).
     */
    public ValidationResult validateContent(WorkspacePolicy policy, Path filePath) {
        try {
            // 파일 크기 체크
            long size = Files.size(filePath);
            if (size > policy.maxFileSize()) {
                return ValidationResult.fail(
                        "파일 크기 초과: " + size + " bytes (최대: " + policy.maxFileSize() + ")", 200);
            }

            // 바이너리 체크 (첫 8KB)
            if (policy.denyBinary()) {
                byte[] head = Files.readAllBytes(filePath);
                int checkSize = (int) Math.min(head.length, 8192);
                for (int i = 0; i < checkSize; i++) {
                    if (head[i] == 0) {
                        return ValidationResult.fail(
                                "바이너리 파일은 읽기가 제한됨: " + filePath.getFileName(), 201);
                    }
                }
            }

            // 시크릿 패턴 체크
            if (!policy.secretPatterns().isEmpty()) {
                String content = Files.readString(filePath);
                for (Pattern pattern : policy.secretPatterns()) {
                    if (pattern.matcher(content).find()) {
                        log.warn("시크릿 패턴 탐지: {} in {}", pattern.pattern(), filePath);
                        return ValidationResult.fail(
                                "시크릿 패턴이 탐지된 파일: " + filePath.getFileName(), 202);
                    }
                }
            }
        } catch (IOException e) {
            return ValidationResult.fail("파일 접근 실패: " + e.getMessage(), 300);
        }

        return ValidationResult.OK;
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
