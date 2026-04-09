package com.platform.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 매뉴얼/가이드 서빙 컨트롤러.
 * docs/guides/ 디렉토리의 마크다운 파일을 목록/상세 API로 제공한다.
 */
@RestController
@RequestMapping("/api/v1/guides")
public class GuideController {

    private static final Logger log = LoggerFactory.getLogger(GuideController.class);

    private final Path guidesDir;

    public GuideController(
            @Value("${aimbase.guides.path:docs/guides}") String guidesPath) {
        this.guidesDir = Paths.get(guidesPath).toAbsolutePath().normalize();
        log.info("GuideController 초기화: path={}", this.guidesDir);
    }

    @GetMapping
    public List<Map<String, String>> listGuides() {
        if (!Files.isDirectory(guidesDir)) {
            log.warn("가이드 디렉토리 없음: {}", guidesDir);
            return List.of();
        }

        List<Map<String, String>> guides = new ArrayList<>();
        try (Stream<Path> files = Files.list(guidesDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .sorted()
                 .forEach(p -> {
                     String filename = p.getFileName().toString();
                     String slug = filename.replace(".md", "");
                     String title = extractTitle(p, filename);
                     guides.add(Map.of(
                             "slug", slug,
                             "title", title,
                             "filename", filename
                     ));
                 });
        } catch (IOException e) {
            log.error("가이드 목록 읽기 실패: {}", e.getMessage());
        }
        return guides;
    }

    @GetMapping("/{slug}")
    public Map<String, String> getGuide(@PathVariable String slug) {
        // 경로 조작 방지
        if (slug.contains("..") || slug.contains("/") || slug.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 slug: " + slug);
        }

        Path file = guidesDir.resolve(slug + ".md");
        if (!Files.exists(file) || !file.startsWith(guidesDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "가이드 없음: " + slug);
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String title = extractTitle(file, slug);
            return Map.of(
                    "slug", slug,
                    "title", title,
                    "content", content
            );
        } catch (IOException e) {
            log.error("가이드 읽기 실패: {} — {}", slug, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 읽기 실패");
        }
    }

    /** 파일 첫 줄에서 # 제목 추출 */
    private String extractTitle(Path file, String fallback) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }
}
