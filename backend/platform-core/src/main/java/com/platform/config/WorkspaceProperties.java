package com.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public String getBase() { return base; }
    public void setBase(String base) { this.base = base; }

    public List<String> getWhitelistRoots() { return whitelistRoots; }
    public void setWhitelistRoots(List<String> whitelistRoots) { this.whitelistRoots = whitelistRoots; }

    /**
     * 화이트리스트를 절대경로 Path 목록으로 정규화. 홈 디렉토리(~) 전개 포함.
     */
    public List<Path> whitelistPaths() {
        if (whitelistRoots == null || whitelistRoots.isEmpty()) return List.of();
        return whitelistRoots.stream()
                .map(WorkspaceProperties::expandHome)
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
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
