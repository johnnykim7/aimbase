package com.platform.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-178: 대화 요청 한 번을 저장할 때 같이 남기는 위젯 값.
 *
 * @param chatProjectId   세션을 처음 만들 때 넣을 위젯 프로젝트. 이미 있는 세션이면 호출자가 null 로 준다
 * @param questionDisplay 질문 줄의 이어보기 화면용 값({@code text}, {@code attachments}). 저장된 질문 블록에는
 *                        첨부 번호가 없고 서버가 덧붙인 안내 글이 섞여 있어 따로 둔다
 * @param toolNames       이번 답에서 쓴 도구 이름. 채팅 대화 기록에는 도구 호출 줄을 남기지 않아 답 줄에 이름만 둔다
 * @param screenKey       CR-194: 대화를 처음 저장할 때 남길 화면 키(BIZ-277). 없으면 null
 */
public record TurnExtras(UUID chatProjectId, Map<String, Object> questionDisplay, List<String> toolNames,
                         String screenKey) {

    public static final TurnExtras NONE = new TurnExtras(null, null, List.of(), null);

    public TurnExtras {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }

    public TurnExtras(UUID chatProjectId, Map<String, Object> questionDisplay, List<String> toolNames) {
        this(chatProjectId, questionDisplay, toolNames, null);
    }
}
