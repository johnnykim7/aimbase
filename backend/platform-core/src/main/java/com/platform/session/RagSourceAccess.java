package com.platform.session;

import com.platform.repository.KnowledgeSourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * CR-206 BIZ-290: 위젯 토큰에 허용 자료 목록이 있으면 대화와 청크 원문 조회는 그 목록 안의 자료만 쓴다.
 * 목록 밖 자료와 테넌트에 없는 자료를 같은 404 로 거절해 다른 사무소 자료가 있다는 것을 알리지 않는다(BIZ-275 와 같은 생각).
 * 목록이 없는 위젯 토큰과 로그인은 테넌트의 어느 자료든 쓴다. knowledge_sources 는 테넌트 DB 표라 존재 확인이 곧 테넌트 안 확인이다.
 */
@Component
public class RagSourceAccess {

    public static final String NOT_FOUND = "rag_source_not_found";
    public static final String TOKEN_INVALID = "widget_token_invalid:rag_source_id";
    /** knowledge_sources.id 열 길이 */
    public static final int ID_MAX = 100;
    public static final int LIST_MAX = 20;

    private final KnowledgeSourceRepository sources;

    public RagSourceAccess(KnowledgeSourceRepository sources) {
        this.sources = sources;
    }

    /**
     * 발급 요청의 허용 자료 목록을 정리한다. 빈 값과 중복은 빼고, 남은 자료는 모두 이 테넌트에 있어야 한다.
     * 칸이 없거나 비면 빈 목록(허용 자료 없는 토큰).
     */
    public List<String> forToken(List<String> requested) {
        if (requested == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : requested) {
            String id = ChatKeys.blankToNull(raw);
            if (id == null) continue;
            if (id.length() > ID_MAX) throw invalid();
            ids.add(id);
        }
        if (ids.size() > LIST_MAX) throw invalid();
        for (String id : ids) {
            if (!sources.existsById(id)) throw invalid();
        }
        return List.copyOf(ids);
    }

    /**
     * 대화 요청에 쓸 자료를 정한다. 허용 목록이 있는 토큰은 비우면 첫 자료, 목록 밖이면 404.
     * 목록이 없으면 비우면 검색 없음(null), 있으면 그 자료. 어느 쪽이든 테넌트에 없는 자료는 404.
     */
    public String resolve(String requested) {
        String id = ChatKeys.blankToNull(requested);
        List<String> allowed = ChatOwner.currentRagSourceIds();
        if (allowed.isEmpty()) {
            if (id == null) return null;
        } else if (id == null) {
            id = allowed.get(0);
        } else if (!allowed.contains(id)) {
            throw notFound();
        }
        if (!sources.existsById(id)) throw notFound();
        return id;
    }

    /** 청크 원문 조회. 허용 목록이 있는 토큰은 목록 안 자료만 읽는다. 자료가 있는지는 창구가 본다. */
    public void requireReadable(String sourceId) {
        List<String> allowed = ChatOwner.currentRagSourceIds();
        if (!allowed.isEmpty() && !allowed.contains(sourceId)) throw notFound();
    }

    private static ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }

    private static ChatRejectedException invalid() {
        return new ChatRejectedException(HttpStatus.BAD_REQUEST, TOKEN_INVALID);
    }
}
