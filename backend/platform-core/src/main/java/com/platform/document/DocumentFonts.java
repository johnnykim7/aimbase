package com.platform.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PDF 에 심을 글꼴 목록. api 이미지에는 fonts-nanum 이 깔리고, 맥 로컬은 사용자 글꼴 폴더를 가리킨다.
 *
 * <p>글꼴 이름을 코드에 적지 않으려고 폴더 안 TTF 를 모두 등록한다. 파일 이름에서 굵기 낱말을 떼어
 * 가족 이름을 만들고 굵기를 읽는다(NanumGothicBold 는 NanumGothic 700). 같은 가족과 굵기가 겹치면
 * 이름 순으로 앞의 것만 쓴다.
 */
@Component
public class DocumentFonts {

    private static final Logger log = LoggerFactory.getLogger(DocumentFonts.class);

    private static final Pattern WEIGHT_SUFFIX = Pattern.compile(
            "(?i)[-_ ]?(extrabold|ultrabold|semibold|demibold|extralight|ultralight|bold|light|medium|thin|black|heavy|regular)$");

    public record Face(Path file, String family, int weight) {}

    private final Path dir;
    private volatile List<Face> faces;

    public DocumentFonts(@Value("${aimbase.document.pdf-font-dir:/usr/share/fonts/truetype/nanum}") String dir) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    public boolean available() {
        return !faces().isEmpty();
    }

    public List<Face> faces() {
        List<Face> loaded = faces;
        if (loaded == null) {
            loaded = scan();
            faces = loaded;
        }
        return loaded;
    }

    /** 본문 가족 이름. 이름 순이라 같은 폴더면 늘 같은 글꼴이 앞에 선다. */
    public List<String> sansFamilies() {
        Set<String> out = new LinkedHashSet<>();
        for (Face f : faces()) {
            if (!isMono(f.family())) out.add(f.family());
        }
        return List.copyOf(out);
    }

    public List<String> monoFamilies() {
        Set<String> out = new LinkedHashSet<>();
        for (Face f : faces()) {
            if (isMono(f.family())) out.add(f.family());
        }
        return List.copyOf(out);
    }

    private List<Face> scan() {
        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("[CR182] PDF 글꼴 폴더 없음: {}", dir);
            return List.of();
        }
        List<Face> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".ttf") || lower.contains("italic") || lower.contains("oblique")) continue;
                String base = name.substring(0, name.length() - 4);
                Face face = new Face(p, family(base), weight(base));
                if (face.family().isBlank()) continue;
                if (seen.add(face.family() + "#" + face.weight())) out.add(face);
            }
        } catch (IOException e) {
            log.warn("[CR182] PDF 글꼴 폴더 읽기 실패 {}: {}", dir, e.getMessage());
            return List.of();
        }
        log.info("[CR182] PDF 글꼴 {}개 등록 ({})", out.size(), dir);
        return List.copyOf(out);
    }

    static String family(String base) {
        String name = base;
        String prev;
        do {
            prev = name;
            name = WEIGHT_SUFFIX.matcher(name).replaceAll("");
        } while (!name.equals(prev) && !name.isBlank());
        return name.isBlank() ? base : name.trim();
    }

    static int weight(String base) {
        String n = base.toLowerCase(Locale.ROOT);
        if (n.contains("extrabold") || n.contains("ultrabold")) return 800;
        if (n.contains("semibold") || n.contains("demibold")) return 600;
        if (n.contains("extralight") || n.contains("ultralight")) return 200;
        if (n.contains("black") || n.contains("heavy")) return 900;
        if (n.contains("bold")) return 700;
        if (n.contains("light")) return 300;
        if (n.contains("medium")) return 500;
        if (n.contains("thin")) return 100;
        return 400;
    }

    private static boolean isMono(String family) {
        String f = family.toLowerCase(Locale.ROOT);
        return f.contains("coding") || f.contains("mono");
    }
}
