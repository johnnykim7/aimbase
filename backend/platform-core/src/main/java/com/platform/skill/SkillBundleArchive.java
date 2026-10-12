package com.platform.skill;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-147: 스킬 묶음 zip 올리기 검사 (BIZ-118, T3-17 3.2).
 *
 * <p>하위 폴더 이름과 파일 종류는 정하지 않는다. 어떤 스킬이 와도 같은 방법으로 받는다.</p>
 */
@Component
public class SkillBundleArchive {

    static final String SKILL_FILE = "SKILL.md";
    static final String SCRIPTS_FOLDER = "scripts";
    static final int MAX_PATH_CHARS = 512;

    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n(.*))?\\z", Pattern.DOTALL);
    private static final Pattern BACKTICK_PATH =
            Pattern.compile("`((?:\\./)?[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)`");
    private static final Pattern LINK_PATH = Pattern.compile("\\]\\(([^)\\s#]+)(?:#[^)]*)?\\)");
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private final SkillBundleSettings settings;

    public SkillBundleArchive(SkillBundleSettings settings) {
        this.settings = settings;
    }

    public ParsedSkillBundle parse(byte[] zip) {
        long bundleMax = settings.bundleMaxBytes();
        if (zip.length > bundleMax) {
            throw new SkillBundleRejectedException("zip 크기 " + zip.length + " bytes 가 상한 " + bundleMax + " bytes 를 넘습니다");
        }
        Map<String, byte[]> entries = readEntries(zip, bundleMax);

        String topFolder = locateSkillRoot(entries);
        String prefix = topFolder == null ? "" : topFolder + "/";
        TreeMap<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            files.put(e.getKey().substring(prefix.length()), e.getValue());
        }

        String skillText = decodeUtf8(files.get(SKILL_FILE));
        if (skillText == null) {
            throw new SkillBundleRejectedException("SKILL.md 가 UTF-8 텍스트가 아닙니다");
        }
        Matcher m = FRONTMATTER.matcher(stripBom(skillText));
        if (!m.matches()) {
            throw new SkillBundleRejectedException("SKILL.md 맨 앞에 --- 로 감싼 앞머리가 없습니다");
        }
        Map<String, Object> frontmatter = parseFrontmatter(m.group(1));
        String name = requireName(frontmatter.get("name"));
        String description = requireDescription(frontmatter.get("description"));
        String instructions = m.group(2) == null ? "" : m.group(2).replaceFirst("\\A(?:[ \\t]*\\r?\\n)+", "");

        List<ParsedSkillBundle.File> parsedFiles = new ArrayList<>();
        long total = 0;
        MessageDigest contentDigest = sha256();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] content = e.getValue();
            String fileSha = hex(sha256().digest(content));
            parsedFiles.add(new ParsedSkillBundle.File(e.getKey(), content, fileSha, decodeUtf8(content) != null && !hasNul(content)));
            total += content.length;
            contentDigest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            contentDigest.update((byte) 0);
            contentDigest.update(fileSha.getBytes(StandardCharsets.US_ASCII));
            contentDigest.update((byte) '\n');
        }

        List<Map<String, Object>> warnings = collectWarnings(name, topFolder, instructions, files.keySet());
        return new ParsedSkillBundle(name, description, frontmatter, instructions, parsedFiles, total,
                hex(contentDigest.digest()), warnings, topFolder);
    }

    private Map<String, byte[]> readEntries(byte[] zip, long bundleMax) {
        int maxFiles = settings.bundleMaxFiles();
        long fileMax = settings.fileMaxBytes();
        List<String> ignored = settings.ignoredEntries();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipFile zf = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(zip)).get()) {
            for (ZipArchiveEntry entry : Collections.list(zf.getEntries())) {
                String path = normalizePath(entry.getName());
                if (entry.isUnixSymlink()) {
                    throw new SkillBundleRejectedException("심볼릭 링크는 받지 않습니다: " + entry.getName());
                }
                if (entry.isDirectory() || path.isEmpty() || isIgnored(path, ignored)) continue;
                if (entries.size() >= maxFiles) {
                    throw new SkillBundleRejectedException("파일 수가 상한 " + maxFiles + " 개를 넘습니다");
                }
                byte[] content;
                try (InputStream in = zf.getInputStream(entry)) {
                    content = readBounded(in, fileMax, path);
                }
                total += content.length;
                if (total > bundleMax) {
                    throw new SkillBundleRejectedException("푼 합계가 상한 " + bundleMax + " bytes 를 넘습니다");
                }
                if (entries.putIfAbsent(path, content) != null) {
                    throw new SkillBundleRejectedException("같은 경로가 두 번 있습니다: " + path);
                }
            }
        } catch (IOException e) {
            throw new SkillBundleRejectedException("zip 으로 읽을 수 없습니다: " + e.getMessage());
        }
        if (entries.isEmpty()) {
            throw new SkillBundleRejectedException("파일이 없습니다");
        }
        return entries;
    }

    /** 맨 위에 SKILL.md 가 있으면 null, 하나뿐인 최상위 폴더 바로 아래에 있으면 그 폴더 이름. */
    static String locateSkillRoot(Map<String, byte[]> entries) {
        if (entries.containsKey(SKILL_FILE)) return null;
        Set<String> tops = new HashSet<>();
        for (String path : entries.keySet()) {
            int slash = path.indexOf('/');
            tops.add(slash < 0 ? "" : path.substring(0, slash));
        }
        if (tops.size() == 1) {
            String top = tops.iterator().next();
            if (!top.isEmpty() && entries.containsKey(top + "/" + SKILL_FILE)) return top;
        }
        throw new SkillBundleRejectedException("SKILL.md 가 맨 위나 하나뿐인 최상위 폴더 바로 아래에 없습니다");
    }

    static String normalizePath(String raw) {
        if (raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) {
            throw new SkillBundleRejectedException("경로에 쓸 수 없는 글자가 있습니다: " + raw);
        }
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) {
            throw new SkillBundleRejectedException("절대 경로는 받지 않습니다: " + raw);
        }
        StringJoiner joiner = new StringJoiner("/");
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                throw new SkillBundleRejectedException("묶음 밖을 가리키는 경로는 받지 않습니다: " + raw);
            }
            joiner.add(segment);
        }
        String path = joiner.toString();
        if (path.length() > MAX_PATH_CHARS) {
            throw new SkillBundleRejectedException("경로가 " + MAX_PATH_CHARS + " 자를 넘습니다: " + raw);
        }
        return path;
    }

    /** 끝이 / 인 항목은 어느 깊이든 그 이름의 폴더, 아니면 그 이름의 파일을 뺀다. */
    static boolean isIgnored(String path, List<String> ignored) {
        String[] segments = path.split("/");
        for (String rule : ignored) {
            if (rule.endsWith("/")) {
                String folder = rule.substring(0, rule.length() - 1);
                for (int i = 0; i < segments.length - 1; i++) {
                    if (segments[i].equals(folder)) return true;
                }
            } else if (segments[segments.length - 1].equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readBounded(InputStream in, long max, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            read += n;
            if (read > max) {
                throw new SkillBundleRejectedException("파일 하나가 상한 " + max + " bytes 를 넘습니다: " + path);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Map<String, Object> parseFrontmatter(String yamlText) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        } catch (YAMLException e) {
            throw new SkillBundleRejectedException("앞머리를 YAML 로 읽을 수 없습니다: " + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new SkillBundleRejectedException("앞머리가 키와 값의 모음이 아닙니다");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), jsonSafe(v)));
        return result;
    }

    /** JSONB 에 담을 수 있게 YAML 날짜 등은 문자열로 바꾼다. */
    private static Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), jsonSafe(v)));
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(jsonSafe(v)));
            return out;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return String.valueOf(value);
    }

    private static String requireName(Object value) {
        if (!(value instanceof String name) || !NAME.matcher(name).matches()) {
            throw new SkillBundleRejectedException("앞머리 name 은 소문자, 숫자, 하이픈으로 64자 이하여야 합니다");
        }
        return name;
    }

    private String requireDescription(Object value) {
        if (!(value instanceof String description) || description.isBlank()) {
            throw new SkillBundleRejectedException("앞머리 description 이 없습니다");
        }
        int max = settings.descriptionMaxChars();
        int chars = description.codePointCount(0, description.length());
        if (chars > max) {
            throw new SkillBundleRejectedException("앞머리 description 이 " + chars + " 자로 상한 " + max + " 자를 넘습니다");
        }
        return description.strip();
    }

    private List<Map<String, Object>> collectWarnings(String name, String topFolder, String instructions,
                                                      Set<String> paths) {
        List<Map<String, Object>> warnings = new ArrayList<>();

        Set<String> folders = new HashSet<>();
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash > 0) folders.add(path.substring(0, slash));
        }
        Set<String> references = new LinkedHashSet<>();
        Matcher backtick = BACKTICK_PATH.matcher(instructions);
        while (backtick.find()) {
            String ref = backtick.group(1);
            if (ref.substring(ref.lastIndexOf('/') + 1).contains(".")) references.add(ref);
        }
        Matcher link = LINK_PATH.matcher(instructions);
        while (link.find()) references.add(link.group(1));

        for (String ref : references) {
            String path = ref.startsWith("./") ? ref.substring(2) : ref;
            if (path.startsWith("/") || path.contains("..") || URI_SCHEME.matcher(path).find()) continue;
            int slash = path.indexOf('/');
            // 묶음에 없는 폴더(작업장 산출물 경로 등)는 묶음 파일을 가리킨 것이 아니다
            if (slash <= 0 || !folders.contains(path.substring(0, slash))) continue;
            if (paths.contains(path) || isFolder(path, paths)) continue;
            warnings.add(warning(SkillBundleWarningCode.MISSING_REFERENCE, "path", path));
        }

        if (topFolder != null && !topFolder.equals(name)) {
            Map<String, Object> w = warning(SkillBundleWarningCode.FOLDER_NAME_MISMATCH, "folder", topFolder);
            w.put("name", name);
            warnings.add(w);
        }

        List<String> runnable = settings.runnableExtensions().stream()
                .map(ext -> ext.toLowerCase(Locale.ROOT)).toList();
        for (String path : paths) {
            if (!path.startsWith(SCRIPTS_FOLDER + "/")) continue;
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            String ext = dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (!runnable.contains(ext)) {
                warnings.add(warning(SkillBundleWarningCode.UNSUPPORTED_SCRIPT, "path", path));
            }
        }
        return warnings;
    }

    private static boolean isFolder(String path, Set<String> paths) {
        String prefix = path.endsWith("/") ? path : path + "/";
        for (String p : paths) {
            if (p.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Map<String, Object> warning(SkillBundleWarningCode code, String key, String value) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code.name());
        w.put(key, value);
        return w;
    }

    /** UTF-8 로 풀리지 않으면 null. */
    static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean hasNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return true;
        }
        return false;
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
