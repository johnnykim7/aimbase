package com.platform.session;

import com.platform.config.PlatformSettingsService;
import com.platform.document.DocModel;
import com.platform.document.DocumentRenderer;
import com.platform.document.FileMediaTypes;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.tool.builtin.CreateFileTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 응답 저장과 대화 내보내기(CR-182 3.3, 3.4, BIZ-211). 그려서 바로 내려주고 서버에 남기지 않는다.
 * 공유 사본(3.5)도 대화 그리기를 여기서 가져간다.
 */
@Service
public class ChatExportService {

    public static final String DEFAULT_TZ_KEY = "chat.export.default-timezone";
    static final String ERR_FORMAT = "export_invalid:format";
    static final String ERR_EMPTY = "export_empty";
    static final String TURN_NOT_FOUND = "turn_not_found";

    private static final List<String> ANSWER_FORMATS = List.of("pdf", "docx");
    private static final List<String> CONVERSATION_FORMATS = List.of("pdf", "docx", "md");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter SHOWN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChatSessionOwnership ownership;
    private final ChatTurnLookup turnLookup;
    private final ConversationMessageRepository messageRepository;
    private final DocumentRenderer renderer;
    private final ChatExportLabels labels;
    private final PlatformSettingsService settings;
    private final Clock clock;

    @Autowired
    public ChatExportService(ChatSessionOwnership ownership, ChatTurnLookup turnLookup,
                             ConversationMessageRepository messageRepository, DocumentRenderer renderer,
                             ChatExportLabels labels, PlatformSettingsService settings) {
        this(ownership, turnLookup, messageRepository, renderer, labels, settings, Clock.systemUTC());
    }

    ChatExportService(ChatSessionOwnership ownership, ChatTurnLookup turnLookup,
                      ConversationMessageRepository messageRepository, DocumentRenderer renderer,
                      ChatExportLabels labels, PlatformSettingsService settings, Clock clock) {
        this.ownership = ownership;
        this.turnLookup = turnLookup;
        this.messageRepository = messageRepository;
        this.renderer = renderer;
        this.labels = labels;
        this.settings = settings;
        this.clock = clock;
    }

    /** 내려줄 파일 한 개. */
    public record Rendered(String fileName, String contentType, byte[] bytes) {}

    /** 3.3 응답 하나. 질문은 넣지 않는다. */
    public Rendered saveAnswer(String requesterRef, String sessionId, String turnId, String format, String tz) {
        String fmt = format(format, ANSWER_FORMATS);
        ChatTurnLookup.Turn turn = turnLookup.find(requesterRef, sessionId, turnId);
        List<ConversationMessageEntity> answers = turn.messages().stream()
                .filter(m -> m.getDeletedAt() == null && isText(m) && "assistant".equals(m.getRole()))
                .toList();
        String body = String.join("\n\n", answers.stream()
                .map(ConversationMessageEntity::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList());
        if (body.isBlank()) throw new ChatRejectedException(HttpStatus.NOT_FOUND, TURN_NOT_FOUND);

        ZoneId zone = zone(tz);
        Map<String, String> l = labels.current();
        OffsetDateTime answeredAt = answers.get(answers.size() - 1).getCreatedAt();
        String title = title(turn.session(), l);
        List<String> meta = new ArrayList<>();
        meta.add(l.get(ChatExportLabels.ANSWERED_AT) + " " + shown(answeredAt, zone));
        String model = answers.get(answers.size() - 1).getModel();
        if (model != null && !model.isBlank()) meta.add(l.get(ChatExportLabels.MODEL) + " " + model);
        DocModel doc = new DocModel(title, null, meta, List.of(new DocModel.Section(null, body)));
        return render(doc, fmt, fileName(title, answeredAt, zone, fmt));
    }

    /** 3.4 대화 전체. */
    public Rendered exportConversation(String requesterRef, String sessionId, String format, String tz) {
        String fmt = format(format, CONVERSATION_FORMATS);
        ConversationSessionEntity session = ownership.require(requesterRef, sessionId);
        ZoneId zone = zone(tz);
        OffsetDateTime now = OffsetDateTime.now(clock);
        DocModel doc = conversation(session, zone, now);
        return render(doc, fmt, fileName(doc.title(), now, zone, fmt));
    }

    /**
     * 살아 있는 질문과 응답을 순서대로 칸으로 나눈다. 생각, 도구 사용, 압축 경계는 뺀다. 메시지가 없으면 409.
     */
    public DocModel conversation(ConversationSessionEntity session, ZoneId zone, OffsetDateTime exportedAt) {
        Map<String, String> l = labels.current();
        List<DocModel.Section> sections = new ArrayList<>();
        for (ConversationMessageEntity m : messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId())) {
            if (!isText(m)) continue;
            boolean user = "user".equals(m.getRole());
            if (!user && !"assistant".equals(m.getRole())) continue;
            String text = m.getContent() == null ? "" : m.getContent();
            if (user) text = withAttachmentNames(text, m.getContentJson(), l);
            if (text.isBlank()) continue;
            String head = l.get(user ? ChatExportLabels.QUESTION : ChatExportLabels.ANSWER)
                    + " " + shown(m.getCreatedAt(), zone);
            sections.add(new DocModel.Section(head, text));
        }
        if (sections.isEmpty()) throw new ChatRejectedException(HttpStatus.CONFLICT, ERR_EMPTY);
        List<String> meta = List.of(
                l.get(ChatExportLabels.EXPORTED_AT) + " " + shown(exportedAt, zone) + " (" + zone.getId() + ")",
                l.get(ChatExportLabels.MESSAGE_COUNT) + " " + sections.size());
        return new DocModel(title(session, l), null, meta, sections);
    }

    public ZoneId zone(String tz) {
        if (tz != null && !tz.isBlank()) {
            try {
                return ZoneId.of(tz.strip());
            } catch (DateTimeException ignored) {
                // 위젯이 보낸 시간대가 틀리면 기본 시간대로 적는다(3.3).
            }
        }
        try {
            return ZoneId.of(settings.getString(DEFAULT_TZ_KEY, "Asia/Seoul"));
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    public static String shown(OffsetDateTime t, ZoneId zone) {
        return t == null ? "" : t.atZoneSameInstant(zone).format(SHOWN_TIME);
    }

    public String title(ConversationSessionEntity session, Map<String, String> l) {
        String t = session.getTitle();
        return t == null || t.isBlank() ? l.get(ChatExportLabels.UNTITLED) : t.strip();
    }

    private Rendered render(DocModel doc, String fmt, String fileName) {
        byte[] bytes = switch (fmt) {
            case "pdf" -> renderer.pdf(doc);
            case "docx" -> renderer.docx(doc);
            default -> renderer.markdown(doc).getBytes(StandardCharsets.UTF_8);
        };
        return new Rendered(fileName, FileMediaTypes.forName(fileName), bytes);
    }

    private static String fileName(String title, OffsetDateTime at, ZoneId zone, String ext) {
        String base = CreateFileTool.cleanName(title == null ? "" : title, ext);
        String time = (at == null ? OffsetDateTime.now(zone) : at).atZoneSameInstant(zone).format(FILE_TIME);
        return (base.isEmpty() ? time : base + "_" + time) + "." + ext;
    }

    private static String format(String format, List<String> allowed) {
        String f = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(f)) throw new ChatRejectedException(HttpStatus.BAD_REQUEST, ERR_FORMAT);
        return f;
    }

    private static boolean isText(ConversationMessageEntity m) {
        return m.getMessageType() == null || ConversationMessageEntity.TYPE_TEXT.equals(m.getMessageType());
    }

    /** 첨부는 이름만 적는다(3.4). 그림은 이름이 없어 낱말 하나로 적는다. */
    private static String withAttachmentNames(String text, List<Map<String, Object>> blocks, Map<String, String> l) {
        if (blocks == null || blocks.isEmpty()) return text;
        List<String> names = new ArrayList<>();
        for (Map<String, Object> b : blocks) {
            Object type = b.get("type");
            if ("document".equals(type)) {
                Object name = b.get("filename");
                names.add(name == null || name.toString().isBlank() ? l.get(ChatExportLabels.ATTACHMENT) : name.toString());
            } else if ("image".equals(type)) {
                names.add(l.get(ChatExportLabels.IMAGE));
            }
        }
        if (names.isEmpty()) return text;
        String line = "*" + l.get(ChatExportLabels.ATTACHMENT) + ": " + String.join(", ", names) + "*";
        return text.isBlank() ? line : text + "\n\n" + line;
    }
}
