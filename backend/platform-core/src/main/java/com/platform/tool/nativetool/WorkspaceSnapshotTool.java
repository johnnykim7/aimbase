package com.platform.tool.nativetool;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.workspace.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * CR-029 (PRD-173): 워크스페이스 구조, 주요 파일군, 최근 변경, git 상태를 요약.
 */
@Component
public class WorkspaceSnapshotTool implements EnhancedToolExecutor {

    private final WorkspaceResolver workspaceResolver;

    public WorkspaceSnapshotTool(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "builtin_workspace_snapshot",
                "워크스페이스의 디렉토리 구조, 언어/프레임워크 힌트, 최근 변경, git 상태를 요약합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "depth", Map.of("type", "integer", "default", 3, "description", "디렉토리 탐색 깊이"),
                                "include_git_status", Map.of("type", "boolean", "default", true),
                                "include_recent_changes", Map.of("type", "boolean", "default", true),
                                "recent_limit", Map.of("type", "integer", "default", 10)
                        )
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("builtin_workspace_snapshot", List.of("workspace", "summary"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        long start = System.currentTimeMillis();
        int depth = input.containsKey("depth") ? ((Number) input.get("depth")).intValue() : 3;
        boolean includeGit = !Boolean.FALSE.equals(input.get("include_git_status"));
        boolean includeRecent = !Boolean.FALSE.equals(input.get("include_recent_changes"));
        int recentLimit = input.containsKey("recent_limit") ? ((Number) input.get("recent_limit")).intValue() : 10;

        Path root = workspaceResolver.getWorkspaceRoot(ctx);
        if (!Files.isDirectory(root)) {
            return ToolResult.error("워크스페이스 경로가 존재하지 않습니다: " + root)
                    .withDuration(System.currentTimeMillis() - start);
        }

        Map<String, Object> output = new LinkedHashMap<>();

        // 1. 디렉토리 트리
        StringBuilder tree = new StringBuilder();
        Map<String, Integer> extCount = new HashMap<>();
        int[] fileCount = {0};
        try {
            buildTree(root, root, depth, 0, tree, extCount, fileCount);
        } catch (IOException e) {
            tree.append("트리 생성 실패: ").append(e.getMessage());
        }
        output.put("tree", tree.toString());
        output.put("fileCount", fileCount[0]);

        // 2. 언어/프레임워크 추론
        output.put("languageHint", inferLanguage(extCount));
        output.put("frameworkHint", inferFramework(root));

        // 3. Git 상태
        if (includeGit) {
            output.put("gitBranch", runCommand(root, "git", "branch", "--show-current"));
            output.put("gitStatus", runCommand(root, "git", "status", "--short"));
        }

        // 4. 최근 변경
        if (includeRecent) {
            String recentLog = runCommand(root, "git", "log", "--oneline", "-" + recentLimit);
            output.put("recentChanges", recentLog);
        }

        long durationMs = System.currentTimeMillis() - start;
        String summary = String.format("워크스페이스: %d개 파일, %s, %s",
                fileCount[0], output.get("languageHint"), output.get("frameworkHint"));

        return new ToolResult(true, output, summary,
                List.of(), List.of(), Map.of("fileCount", fileCount[0]),
                null, durationMs);
    }

    private void buildTree(Path root, Path current, int maxDepth, int currentDepth,
                           StringBuilder sb, Map<String, Integer> extCount, int[] fileCount) throws IOException {
        if (currentDepth > maxDepth) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            entries.sort(Comparator.comparing(Path::getFileName));

            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || name.equals("node_modules") || name.equals("build")
                        || name.equals("target") || name.equals("__pycache__") || name.equals("dist")) {
                    continue;
                }

                String indent = "  ".repeat(currentDepth);
                if (Files.isDirectory(entry)) {
                    sb.append(indent).append(name).append("/\n");
                    buildTree(root, entry, maxDepth, currentDepth + 1, sb, extCount, fileCount);
                } else {
                    sb.append(indent).append(name).append("\n");
                    fileCount[0]++;
                    String ext = getExtension(name);
                    if (!ext.isEmpty()) {
                        extCount.merge(ext, 1, Integer::sum);
                    }
                }
            }
        }
    }

    private String inferLanguage(Map<String, Integer> extCount) {
        return extCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> switch (e.getKey()) {
                    case "java" -> "Java";
                    case "ts", "tsx" -> "TypeScript";
                    case "js", "jsx" -> "JavaScript";
                    case "py" -> "Python";
                    case "go" -> "Go";
                    case "rs" -> "Rust";
                    case "kt" -> "Kotlin";
                    default -> e.getKey();
                })
                .orElse("unknown");
    }

    private String inferFramework(Path root) {
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts")))
            return "Gradle";
        if (Files.exists(root.resolve("pom.xml"))) return "Maven";
        if (Files.exists(root.resolve("package.json"))) return "Node.js";
        if (Files.exists(root.resolve("requirements.txt")) || Files.exists(root.resolve("pyproject.toml")))
            return "Python";
        if (Files.exists(root.resolve("go.mod"))) return "Go";
        if (Files.exists(root.resolve("Cargo.toml"))) return "Rust";
        return "unknown";
    }

    private String runCommand(Path workDir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = r.readLine()) != null && lines++ < 50) {
                    sb.append(line).append("\n");
                }
                output = sb.toString().trim();
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
