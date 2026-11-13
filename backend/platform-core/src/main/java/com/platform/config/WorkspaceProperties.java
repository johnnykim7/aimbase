package com.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CR-045: 워크스페이스 설정의 단일 진실 원천.
 *
 * L0(ChatController), L1(WorkspaceResolver), L2(WorkspacePolicyEngine)
 * 3개 방어선이 모두 이 객체를 참조한다.
 */
@ConfigurationProperties(prefix = "aimbase.workspace")
public class WorkspaceProperties {

    private String base = "/data/workspaces";

    private List<String> whitelistRoots = new ArrayList<>();

    /**
     * CR-187: 작업 폴더를 api 공용 폴더와 따로 붙인 실행기. 항목은 「실행기 이름=폴더」.
     * api 와 그 실행기가 같은 호스트 폴더를 이 경로로 붙여야, 서버가 잡은 대화 폴더를 실행기가 그대로 쓰고
     * 실행기가 만든 파일을 서버가 그대로 읽는다. 이 폴더들은 허용 폴더에 저절로 든다.
     */
    private Map<String, String> runnerBaseByName = Map.of();

    public String getBase() { return base; }
    public void setBase(String base) { this.base = base; }

    public List<String> getWhitelistRoots() { return whitelistRoots; }
    public void setWhitelistRoots(List<String> whitelistRoots) { this.whitelistRoots = whitelistRoots; }

    public List<String> getRunnerBases() {
        return runnerBaseByName.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
    }

    /** 잘못 적은 항목을 건너뛰면 대화 폴더가 다시 실행기 밖에 잡히므로 기동을 멈춘다. */
    public void setRunnerBases(List<String> entries) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry == null || entry.isBlank()) continue;
                int eq = entry.indexOf('=');
                String name = eq > 0 ? entry.substring(0, eq).trim() : "";
                String dir = eq > 0 ? entry.substring(eq + 1).trim() : "";
                if (name.isEmpty() || dir.isEmpty() || !Path.of(expandHome(dir)).isAbsolute()) {
                    throw new IllegalArgumentException(
                            "aimbase.workspace.runner-bases 항목은 「실행기 이름=절대 경로」여야 합니다: " + entry);
                }
                parsed.put(name, dir);
            }
        }
        this.runnerBaseByName = Map.copyOf(parsed);
    }

    /** @return 그 실행기 작업 폴더. 따로 붙이지 않은 실행기나 이름이 없으면 null. */
    public String runnerBase(String runnerName) {
        if (runnerName == null || runnerName.isBlank()) return null;
        return runnerBaseByName.get(runnerName.trim());
    }

    /**
     * 화이트리스트를 절대경로 Path 목록으로 정규화. 홈 디렉토리(~) 전개 포함.
     * 화이트리스트가 비어 있으면 게이트를 끈 것이라 실행기 폴더도 더하지 않는다.
     */
    public List<Path> whitelistPaths() {
        if (whitelistRoots == null || whitelistRoots.isEmpty()) return List.of();
        return Stream.concat(whitelistRoots.stream(), runnerBaseByName.values().stream())
                .map(WorkspaceProperties::expandHome)
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    /**
     * 주어진 경로가 화이트리스트 내부에 있는지 확인.
     * whitelistRoots가 비어 있으면 항상 true (하위 호환: 게이트 비활성).
     */
    public boolean isInsideWhitelist(Path target) {
        List<Path> roots = whitelistPaths();
        if (roots.isEmpty()) return true;
        Path normalized = target.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String expandHome(String raw) {
        if (raw == null) return null;
        if (raw.startsWith("~")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }
}
