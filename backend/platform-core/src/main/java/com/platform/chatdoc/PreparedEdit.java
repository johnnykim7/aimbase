package com.platform.chatdoc;

import com.platform.domain.ChatDocumentEntity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CR-181: 답 전에 검사와 바탕 판 맞추기를 마친 고치기 한 건. 답이 끝나면 DocumentEditService.complete 로 마무리한다.
 * 스트림은 끝 이벤트와 예외 양쪽에서 마무리를 부를 수 있어 한 번만 돌게 한다.
 */
public final class PreparedEdit {

    private final UUID documentId;
    private final ChatDocumentEntity.Kind kind;
    private final String sessionId;
    private final String name;
    private final String lang;
    private final int baseVersion;
    private final String baseSha256;
    private final String instruction;
    private final boolean fileCreate;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public PreparedEdit(UUID documentId, ChatDocumentEntity.Kind kind, String sessionId, String name, String lang,
                        int baseVersion, String baseSha256, String instruction, boolean fileCreate) {
        this.documentId = documentId;
        this.kind = kind;
        this.sessionId = sessionId;
        this.name = name;
        this.lang = lang;
        this.baseVersion = baseVersion;
        this.baseSha256 = baseSha256;
        this.instruction = instruction;
        this.fileCreate = fileCreate;
    }

    public UUID documentId() { return documentId; }
    public ChatDocumentEntity.Kind kind() { return kind; }
    public String sessionId() { return sessionId; }
    public String name() { return name; }
    public String lang() { return lang; }
    public int baseVersion() { return baseVersion; }
    public String baseSha256() { return baseSha256; }
    public String instruction() { return instruction; }

    /** 원본으로 고치는 파일은 create_file 로 다시 만들므로 요청이 file_create 를 싣지 않아도 이 답에는 그 도구를 준다. */
    public boolean fileCreate() { return fileCreate; }

    /** 처음 부를 때만 참. */
    boolean markFinished() {
        return finished.compareAndSet(false, true);
    }
}
