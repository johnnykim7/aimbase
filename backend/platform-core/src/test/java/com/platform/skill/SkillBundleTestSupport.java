package com.platform.skill;

import com.platform.config.PlatformSettingsService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class SkillBundleTestSupport {

    private SkillBundleTestSupport() {}

    /** 설정 행이 없는 상태. 모든 값이 코드 기본값(V69 seed 와 같음)으로 돈다. */
    static PlatformSettingsService defaultSettings() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getStringList(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        return settings;
    }

    static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: \"" + description + "\"\n---\n\n" + body;
    }

    static Map<String, String> entries(String... pathAndContent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) m.put(pathAndContent[i], pathAndContent[i + 1]);
        return m;
    }

    static byte[] zip(Map<String, String> entries) {
        return zip(entries, null, null);
    }

    static byte[] zip(Map<String, String> entries, String symlinkPath, String symlinkTarget) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(e.getKey());
                out.putArchiveEntry(entry);
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
            if (symlinkPath != null) {
                ZipArchiveEntry link = new ZipArchiveEntry(symlinkPath);
                link.setUnixMode(0120777);
                out.putArchiveEntry(link);
                out.write(symlinkTarget.getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
