package com.platform.api;

import com.platform.session.ChatOwner;
import com.platform.session.ChatRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * CR-206 BIZ-291: 지식 자료 API 는 위젯 토큰이 쓰는 창구가 아니다. 출처 카드가 여는 청크 원문 조회만 빼고
 * 403 widget_forbidden. ConversationController 가 관리용 대화 API 에 둔 것과 같은 코드다.
 */
public class KnowledgeWidgetGuard implements HandlerInterceptor {

    public static final String PATH = "/api/v1/knowledge-sources/**";
    public static final String CHUNK_PATH = "/api/v1/knowledge-sources/*/chunks/*";
    public static final String WIDGET_FORBIDDEN = "widget_forbidden";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (ChatOwner.isWidget(ChatOwner.currentPrincipal())) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, WIDGET_FORBIDDEN);
        }
        return true;
    }
}
