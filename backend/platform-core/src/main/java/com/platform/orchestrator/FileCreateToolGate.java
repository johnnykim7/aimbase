package com.platform.orchestrator;

import com.platform.config.PlatformSettingsService;
import com.platform.tool.ToolFilterContext;
import com.platform.tool.builtin.CreateFileTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 대화 요청에 파일 만들기 도구를 줄지 정한다(CR-182 3.2.1, BIZ-210).
 *
 * <p>요청이 {@code file_create} 를 싣지 않으면 지금과 같아야 한다. 도구를 켠 대화(actions_enabled)가
 * 새 도구를 받지 않도록 그때는 create_file 을 뺀다.
 */
@Component
public class FileCreateToolGate {

    public static final String ENABLED_KEY = "chat.file-create.enabled";

    private static final String TOOL = CreateFileTool.NAME;

    private final PlatformSettingsService settings;

    public FileCreateToolGate(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.getString(ENABLED_KEY, "true"));
    }

    /** 이번 요청에 파일 만들기를 준다. */
    public boolean on(ChatRequest request) {
        return request.fileCreate() && enabled();
    }

    /** 도구 고리를 돌린다. CLI 연결은 고리 없이 허용 도구 목록에 더한다. */
    public boolean loop(ChatRequest request, boolean cli) {
        return request.actionsEnabled() || (on(request) && !cli);
    }

    /**
     * 도구 고리에 넘길 도구 거르기.
     *
     * @param base 도구를 켠 대화는 요청의 거르기, 켜지 않은 대화는 이미 연 도구만 허용하는 거르기(없으면 null).
     *             켜지 않은 대화에 요청의 거르기를 넘기면 허용 목록이 비어 모든 도구가 열리므로 넘기지 않는다.
     */
    public ToolFilterContext filter(ChatRequest request, ToolFilterContext base) {
        if (!on(request)) {
            if (!request.actionsEnabled()) return base;
            List<String> exclude = new ArrayList<>(base == null || base.excludeTools() == null
                    ? List.of() : base.excludeTools());
            if (!exclude.contains(TOOL)) exclude.add(TOOL);
            return base == null ? ToolFilterContext.exclude(exclude) : copy(base, base.allowedTools(), exclude);
        }
        if (base == null) {
            return request.actionsEnabled() ? null : ToolFilterContext.allowOnly(List.of(TOOL));
        }
        if (base.allowedTools() == null || base.allowedTools().isEmpty() || base.allowedTools().contains(TOOL)) {
            return base;
        }
        List<String> allowed = new ArrayList<>(base.allowedTools());
        allowed.add(TOOL);
        return copy(base, allowed, base.excludeTools());
    }

    private static ToolFilterContext copy(ToolFilterContext base, List<String> allowed, List<String> exclude) {
        return new ToolFilterContext(allowed, exclude, base.tags(), base.requiredCapabilities(),
                base.maxPermission(), base.readOnlyMode(), base.attachedTools());
    }

    /** 도구 고리를 돌지 않는 CLI 요청에서 허용 도구 목록에 더할 것. */
    public List<String> extraAllowed(ChatRequest request, boolean cli) {
        return cli && !request.actionsEnabled() && on(request) ? List.of(TOOL) : null;
    }
}
