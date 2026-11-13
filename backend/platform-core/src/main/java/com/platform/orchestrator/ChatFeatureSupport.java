package com.platform.orchestrator;

import com.platform.attachment.AttachmentService;
import com.platform.llm.model.UnifiedMessage;
import com.platform.service.PromptTemplateService;
import com.platform.tool.ToolFilterContext;
import com.platform.tool.builtin.DataQueryTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * CR-183: 대화 API 요청에만 붙는 두 가지를 정한다(T3-2 3.5, 3.6).
 *
 * <ul>
 *   <li>계산 도구(data_query)를 붙일지. CLI 는 대화 중간에 올린 파일도 써야 해서 대화 요청이면 붙이고,
 *       API 는 세션에 보관 중인 데이터 파일이 있을 때만 붙인다(BIZ-222).</li>
 *   <li>위젯이 그릴 수 있는 형식마다 테넌트 프롬프트 틀 widget.render.&lt;값&gt; 을 시스템 안내 끝에 덧붙인다(BIZ-223).</li>
 * </ul>
 */
@Component
public class ChatFeatureSupport {

    static final String RENDER_TEMPLATE_PREFIX = "widget.render.";

    private final AttachmentService attachments;
    private final PromptTemplateService templates;

    public ChatFeatureSupport(AttachmentService attachments, PromptTemplateService templates) {
        this.attachments = attachments;
        this.templates = templates;
    }

    /**
     * 계산 도구를 붙인 도구 고르기 맥락. 붙이지 않으면 요청의 것을 그대로 돌려준다.
     * CLI 연결도 데이터 파일이 있을 때만 붙인다. 대화 중에 처음 붙인 파일은 실행기가 통로 도구가 바뀐 것을 보고
     * CLI 를 다시 기동해 연다(CR-182 3.2.2).
     */
    public ToolFilterContext attachDataTools(ChatRequest request, String sessionId) {
        ToolFilterContext base = request.toolFilter();
        if (!request.dataTools() || sessionId == null || sessionId.isBlank()) return base;
        if (!attachments.hasActiveDataFile(sessionId)) return base;
        ToolFilterContext attached = ToolFilterContext.withAttached(base, List.of(DataQueryTool.NAME));
        return attached.attaches(DataQueryTool.NAME) ? attached : base;
    }

    /** 도구를 끈 대화(actions_enabled=false)에서도 계산 도구만은 쓰게 한다. 다른 도구는 하나도 싣지 않는다. */
    public static ToolFilterContext dataQueryOnly(ToolFilterContext attached) {
        return new ToolFilterContext(List.of(DataQueryTool.NAME), attached.excludeTools(), null, null, null, null,
                attached.attachedTools());
    }

    public static boolean attachesDataQuery(ToolFilterContext filter) {
        return filter != null && filter.attaches(DataQueryTool.NAME);
    }

    /**
     * 그릴 수 있는 형식의 안내 틀을 시스템 안내 끝에 덧붙인 메시지 목록. 없는 값과 겹친 값은 넘어간다.
     * 어댑터가 시스템 메시지 글을 이어 붙이므로 앞에 빈 줄을 둔다.
     */
    public List<UnifiedMessage> withRenderGuides(List<UnifiedMessage> messages, List<String> features) {
        if (features == null || features.isEmpty()) return messages;
        List<String> guides = new ArrayList<>();
        for (String feature : new LinkedHashSet<>(features)) {
            String guide = templates.getTemplate(RENDER_TEMPLATE_PREFIX + feature);
            if (guide != null && !guide.isBlank()) guides.add(guide.strip());
        }
        if (guides.isEmpty()) return messages;
        List<UnifiedMessage> out = new ArrayList<>(messages);
        int lastSystem = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).role() == UnifiedMessage.Role.SYSTEM) lastSystem = i;
        }
        String text = (lastSystem >= 0 ? "\n\n" : "") + String.join("\n\n", guides);
        out.add(lastSystem + 1, UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, text));
        return out;
    }
}
