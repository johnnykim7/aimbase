package com.platform.skill;

import com.platform.domain.SkillBundleFileBase;
import com.platform.domain.SkillBundleFileInfo;
import com.platform.domain.SkillBundleVersionBase;
import com.platform.service.PromptTemplateService;
import com.platform.tool.SkillRef;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * CR-147: 모델 도구 skill_open, skill_read 가 돌려줄 글을 만든다(T3-17 3.4).
 * 도구 결과는 API 경로에서 그대로 문자열로 모델에 가므로 구조체가 아니라 글로 만든다.
 */
@Service
public class SkillToolService {

    static final String OPEN_GUIDE_KEY = "tool.skill_open.guide";

    private final SkillCatalog catalog;
    private final SkillBundleSettings settings;
    private final PromptTemplateService prompts;

    public SkillToolService(SkillCatalog catalog, SkillBundleSettings settings, PromptTemplateService prompts) {
        this.catalog = catalog;
        this.settings = settings;
        this.prompts = prompts;
    }

    public record Opened(ResolvedSkill skill, String text) {}

    public Opened open(String name, List<SkillRef> skillSet, String workspacePath) {
        ResolvedSkill skill = catalog.resolveForCall(name, skillSet);
        SkillBundleStore<?, ?, ?> store = catalog.store(skill.scope());
        SkillBundleVersionBase version = store.findVersionById(skill.versionId())
                .orElseThrow(() -> new SkillNotFoundException("BIZ-120: 스킬 " + name + " 판 " + skill.version() + " 이 없습니다"));
        List<SkillBundleFileInfo> files = store.listFiles(skill.versionId());

        StringBuilder out = new StringBuilder();
        out.append("Skill: ").append(skill.name())
                .append(" (scope: ").append(skill.scope().value())
                .append(", version: ").append(skill.version()).append(")\n");
        if (workspacePath != null && !workspacePath.isBlank()) {
            out.append("Bundle directory: ")
                    .append(Path.of(workspacePath).resolve(skill.workspaceDir())).append("/\n");
        }
        String guide = prompts.getTemplate(OPEN_GUIDE_KEY);
        if (guide != null && !guide.isBlank()) {
            out.append('\n').append(guide.strip()).append('\n');
        }

        out.append("\n## Instructions (SKILL.md)\n\n");
        byte[] instructions = version.getInstructions().getBytes(StandardCharsets.UTF_8);
        int max = settings.readMaxBytes();
        if (instructions.length > max) {
            out.append(Utf8.truncate(instructions, max))
                    .append("\n\n[Instructions truncated at ").append(max)
                    .append(" bytes. Read the rest with skill_read path SKILL.md]\n");
        } else {
            out.append(version.getInstructions().strip()).append('\n');
        }

        out.append("\n## Files (").append(files.size()).append(")\n\n");
        for (SkillBundleFileInfo f : files) {
            out.append("- ").append(f.path()).append(" (").append(f.sizeBytes()).append(" bytes")
                    .append(f.isText() ? "" : ", binary").append(")\n");
        }
        return new Opened(skill, out.toString());
    }

    public record Read(ResolvedSkill skill, String path, String text) {}

    /**
     * 텍스트 파일은 줄 번호를 붙이고 skills.read-max-bytes 에서 끊는다. 끊기면 다음 offset 을 알려준다.
     *
     * @param offset 시작 줄(1부터), null 이면 1
     * @param limit 줄 수, null 이면 상한까지
     */
    public Read read(String name, String path, Integer offset, Integer limit, List<SkillRef> skillSet) {
        ResolvedSkill skill = catalog.resolveForCall(name, skillSet);
        SkillBundleFileBase file = catalog.store(skill.scope()).findFile(skill.versionId(), path)
                .orElseThrow(() -> new SkillNotFoundException(
                        "스킬 " + name + " 판 " + skill.version() + " 에 파일이 없습니다: " + path));

        if (!file.isText()) {
            return new Read(skill, path, path + " is not a text file (" + file.getSizeBytes() + " bytes). "
                    + "Use it through skill_run, or from the bundle directory in the workspace.");
        }

        List<String> lines = splitLines(new String(file.getContent(), StandardCharsets.UTF_8));
        int total = lines.size();
        int start = offset == null || offset < 1 ? 1 : offset;
        if (start > total) {
            return new Read(skill, path, path + " has " + total + " lines. offset " + start + " is past the end.");
        }
        int end = limit == null || limit < 1 ? total : Math.min(total, start + limit - 1);
        int maxBytes = settings.readMaxBytes();

        StringBuilder out = new StringBuilder();
        int used = 0;
        int last = start - 1;
        boolean cut = false;
        for (int i = start; i <= end; i++) {
            String row = i + "\t" + lines.get(i - 1) + "\n";
            int rowBytes = row.getBytes(StandardCharsets.UTF_8).length;
            if (used + rowBytes > maxBytes) {
                if (i == start) {
                    // 한 줄이 상한보다 길면 그 줄만 잘라 싣고 다음 줄로 넘어간다. 그러지 않으면 offset 이 나아가지 않는다.
                    out.append(Utf8.truncate(row.getBytes(StandardCharsets.UTF_8), maxBytes)).append('\n');
                    last = i;
                }
                cut = true;
                break;
            }
            out.append(row);
            used += rowBytes;
            last = i;
        }

        if (cut || last < total) {
            out.append("\n[").append(cut ? "Truncated at " + maxBytes + " bytes. " : "")
                    .append("Showing lines ").append(start).append('-').append(last)
                    .append(" of ").append(total).append('.');
            if (last < total) {
                out.append(" Next offset: ").append(last + 1).append('.');
            }
            out.append("]\n");
        }
        return new Read(skill, path, out.toString());
    }

    private static List<String> splitLines(String content) {
        String[] parts = content.split("\n", -1);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) {
            n--;
        }
        String[] lines = new String[n];
        for (int i = 0; i < n; i++) {
            String line = parts[i];
            lines[i] = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
        return List.of(lines);
    }
}
