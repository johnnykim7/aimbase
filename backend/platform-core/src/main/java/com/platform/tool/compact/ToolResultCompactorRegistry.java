package com.platform.tool.compact;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * CR-031 PRD-215: 도구별 축약기 레지스트리.
 *
 * 도구 이름에 맞는 compactor를 선택하여 축약한다.
 * 매칭 compactor가 없으면 기본 truncate 폴백.
 */
@Component
public class ToolResultCompactorRegistry {

    // 파일 읽기 도구
    private static final Set<String> FILE_READ_TOOLS = Set.of(
            "file_read", "document_section_read", "workspace_snapshot");

    // 검색 도구
    private static final Set<String> SEARCH_TOOLS = Set.of("grep", "glob", "structured_search");

    // 셸/프로세스 도구
    private static final Set<String> SHELL_TOOLS = Set.of("bash", "shell_exec");

    /**
     * 도구 결과를 지능형으로 축약한다.
     *
     * @param toolName  도구 이름
     * @param rawOutput 원본 결과
     * @param maxChars  최대 문자 수
     * @return 축약된 결과
     */
    public String compact(String toolName, String rawOutput, int maxChars) {
        if (rawOutput == null || rawOutput.length() <= maxChars) {
            return rawOutput;
        }

        String lower = toolName != null ? toolName.toLowerCase() : "";

        if (FILE_READ_TOOLS.contains(lower)) {
            return compactFileRead(rawOutput, maxChars);
        }
        if (SEARCH_TOOLS.contains(lower)) {
            return compactSearch(rawOutput, maxChars);
        }
        if (SHELL_TOOLS.contains(lower)) {
            return compactShell(rawOutput, maxChars);
        }

        // 기본 폴백: 앞부분 보존
        return defaultTruncate(rawOutput, maxChars);
    }

    /** 축약 필요 여부 판단 */
    public boolean needsCompaction(String rawOutput, int threshold) {
        return rawOutput != null && rawOutput.length() > threshold;
    }

    /**
     * 파일 읽기: 첫 50줄(헤더/import) + 마지막 20줄 보존.
     */
    private String compactFileRead(String rawOutput, int maxChars) {
        String[] lines = rawOutput.split("\n");
        if (lines.length <= 70) return defaultTruncate(rawOutput, maxChars);

        int headLines = 50;
        int tailLines = 20;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(headLines, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("\n... (").append(lines.length - headLines - tailLines).append(" lines omitted) ...\n\n");
        for (int i = Math.max(headLines, lines.length - tailLines); i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }

        String result = sb.toString();
        return result.length() <= maxChars ? result : defaultTruncate(result, maxChars);
    }

    /**
     * 검색(grep/glob): 매칭 라인만 보존, 중복 파일 경로 제거.
     */
    private String compactSearch(String rawOutput, int maxChars) {
        String[] lines = rawOutput.split("\n");
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        for (String line : lines) {
            // 파일 경로:라인번호 패턴에서 경로만 추출하여 중복 제거
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String path = line.substring(0, colonIdx);
                if (!seenPaths.add(path) && sb.length() > maxChars / 2) {
                    continue; // 이미 본 파일의 추가 매칭은 버짓 절반 이후 생략
                }
            }
            sb.append(line).append("\n");
            if (sb.length() >= maxChars) break;
        }

        String result = sb.toString();
        if (result.length() > maxChars) {
            return result.substring(0, maxChars) + "\n...(truncated)";
        }
        return result;
    }

    /**
     * 셸 실행: exit code + 마지막 50줄 보존.
     */
    private String compactShell(String rawOutput, int maxChars) {
        String[] lines = rawOutput.split("\n");
        if (lines.length <= 50) return defaultTruncate(rawOutput, maxChars);

        StringBuilder sb = new StringBuilder();
        sb.append("... (").append(lines.length - 50).append(" lines omitted) ...\n");
        for (int i = Math.max(0, lines.length - 50); i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }

        String result = sb.toString();
        return result.length() <= maxChars ? result : defaultTruncate(result, maxChars);
    }

    private String defaultTruncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        String truncated = text.substring(0, maxChars);
        int nl = truncated.lastIndexOf('\n');
        if (nl > maxChars / 2) truncated = truncated.substring(0, nl);
        return truncated + "\n...(truncated)";
    }
}
