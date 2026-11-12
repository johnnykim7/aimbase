package com.platform.chatdoc;

import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.service.PromptTemplateService;
import com.platform.session.CancellationRegistry;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatWidgetSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CR-181 PRD-480: 옆 창 문서를 대화로 고치기.
 *
 * <p>답 전에 {@link #prepare} 가 요청을 검사하고, 처음 고치는 문서면 문서 행과 1판을 만들고, FILE 은 out/ 을
 * 바탕 판 내용으로 맞추고, 이번 요청에만 붙일 고치기 지시를 만든다. 답이 끝나면 {@link #complete} 가 답(BLOCK)이나
 * out/ 파일(FILE)에서 결과를 읽어 다음 판을 만든다(BIZ-200, BIZ-201).
 */
@Service
public class DocumentEditService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditService.class);

    static final String TEMPLATE_BLOCK = "widget.document-edit.block";
    static final String TEMPLATE_FILE_SOURCE = "widget.document-edit.file-source";
    static final String TEMPLATE_FILE = "widget.document-edit.file";
    static final String TEMPLATE_SELECTION = "widget.document-edit.selection";

    private static final Set<String> BLOCK_LANGS = Set.of("html", "svg");

    private final ChatDocumentService docs;
    private final ConversationMessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final CancellationRegistry cancellationRegistry;
    private final PromptTemplateService promptTemplateService;
    private final ChatWidgetSettingsService widgetSettings;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionGroupSelector connectionGroupSelector;

    public DocumentEditService(ChatDocumentService docs,
                               ConversationMessageRepository messageRepository,
                               ConversationSessionRepository sessionRepository,
                               CancellationRegistry cancellationRegistry,
                               PromptTemplateService promptTemplateService,
                               ChatWidgetSettingsService widgetSettings,
                               ConnectionAdapterFactory connectionAdapterFactory,
                               ConnectionGroupSelector connectionGroupSelector) {
        this.docs = docs;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.cancellationRegistry = cancellationRegistry;
        this.promptTemplateService = promptTemplateService;
        this.widgetSettings = widgetSettings;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.connectionGroupSelector = connectionGroupSelector;
    }

    /**
     * 답 전 검사와 준비. 되감기 뒤에 불러야 한다. 다시 생성은 지운 판을 뺀 채 같은 바탕 판으로 다시 고친다.
     *
     * @throws ChatRejectedException 검사에 걸리면 그 HTTP 상태와 코드(T3-2)
     */
    public PreparedEdit prepare(String sessionId, DocumentEditRequest request,
                                String connectionId, String connectionGroupId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "document_edit_invalid:no_session");
        }
        if (request == null || request.target() == null || request.target().kind() == null) {
            throw invalidTarget();
        }
        if (cancellationRegistry.isActive(sessionId)) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "session_busy");
        }
        return switch (request.target().kind().toLowerCase(Locale.ROOT)) {
            case "block" -> prepareBlock(sessionId, request);
            case "file" -> prepareFile(sessionId, request, connectionId, connectionGroupId);
            default -> throw invalidTarget();
        };
    }

    private PreparedEdit prepareBlock(String sessionId, DocumentEditRequest request) {
        DocumentEditRequest.Target t = request.target();
        String lang = t.lang() != null ? t.lang().toLowerCase(Locale.ROOT) : null;
        if (!BLOCK_LANGS.contains(lang) || t.index() == null || t.index() < 0) throw invalidTarget();
        UUID turnId = parseUuid(t.turnId());
        if (turnId == null) throw notFound();

        ChatDocumentEntity doc = docs.findBlock(sessionId, turnId, t.index()).orElse(null);
        if (doc == null) {
            boolean ownTurn = messageRepository.findBySessionIdAndTurnId(sessionId, turnId).stream()
                    .anyMatch(m -> m.getDeletedAt() == null);
            if (!ownTurn) throw notFound();
            if (t.source() == null || t.source().isBlank()) {
                throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "document_edit_invalid:source");
            }
            try {
                doc = docs.createBlock(sessionId, turnId, t.index(), lang, t.source());
            } catch (DataIntegrityViolationException race) {
                doc = docs.findBlock(sessionId, turnId, t.index()).orElseThrow(() -> race);
            }
        }
        ChatDocumentVersionEntity base = baseVersion(doc, request.baseVersion());
        String instruction = render(TEMPLATE_BLOCK, Map.of("lang", doc.getLang(), "content", base.getContent(),
                "selection", selectionPart(request)));
        return new PreparedEdit(doc.getId(), ChatDocumentEntity.Kind.BLOCK, sessionId, null, doc.getLang(),
                base.getVersion(), base.getSha256(), instruction);
    }

    private PreparedEdit prepareFile(String sessionId, DocumentEditRequest request,
                                     String connectionId, String connectionGroupId) {
        String name = request.target().name();
        if (!isPlainFileName(name)) throw invalidTarget();
        Path file = docs.outFile(sessionId, name);

        ChatDocumentEntity doc = docs.findFile(sessionId, name).orElse(null);
        if (doc == null) {
            if (file == null || !Files.isRegularFile(file)) throw sourceGone();
            Path source = docs.sourceFile(sessionId, name);
            boolean hasSource = source != null && Files.isRegularFile(source);
            if (!hasSource && !binaryEditSupported(connectionId, connectionGroupId)) throw unsupported();
            try {
                doc = docs.createFile(sessionId, name, docs.storeFile(name, file, source));
            } catch (DataIntegrityViolationException race) {
                doc = docs.findFile(sessionId, name).orElseThrow(() -> race);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        ChatDocumentVersionEntity base = baseVersion(doc, request.baseVersion());
        boolean hasSource = base.getSourceStoragePath() != null;
        if (!hasSource && !binaryEditSupported(connectionId, connectionGroupId)) throw unsupported();
        if (file == null) throw sourceGone();
        try {
            docs.writeToOutDir(doc, base);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String instruction = hasSource
                ? render(TEMPLATE_FILE_SOURCE, Map.of("name", name, "source", docs.readSource(base),
                        "selection", selectionPart(request)))
                : render(TEMPLATE_FILE, Map.of("path", com.platform.artifact.ArtifactService.OUT_DIR + "/" + name,
                        "selection", selectionPart(request)));
        return new PreparedEdit(doc.getId(), ChatDocumentEntity.Kind.FILE, sessionId, name, null,
                base.getVersion(), base.getSha256(), instruction);
    }

    /**
     * 답이 끝난 뒤 결과를 판으로 남긴다. 스트림은 끝 이벤트에서 한 번 부른다.
     *
     * @param answerText 답 글 전체
     * @param turnId     저장된 답 번호. 저장에 실패했으면 null
     * @param stopped    사용자가 중지했는지
     * @return 결과. 이미 마무리한 건이면 null
     */
    public DocumentEditResult complete(PreparedEdit edit, String answerText, String turnId, boolean stopped) {
        if (edit == null || !edit.markFinished()) return null;
        UUID turn = parseUuid(turnId);
        try {
            return edit.kind() == ChatDocumentEntity.Kind.BLOCK
                    ? completeBlock(edit, answerText, turn, stopped)
                    : completeFile(edit, turn, stopped);
        } catch (IOException | RuntimeException e) {
            log.warn("[CR181] 고치기 마무리 실패 doc={}: {}", edit.documentId(), e.getMessage());
            restoreLatest(edit);
            return new DocumentEditResult(edit.documentId(), DocumentEditResult.Status.FAILED, null);
        }
    }

    /** 답이 예외로 끝났을 때. FILE 은 out/ 을 마지막 판으로 되돌린다. */
    public void abandon(PreparedEdit edit) {
        if (edit == null || !edit.markFinished()) return;
        restoreLatest(edit);
    }

    private DocumentEditResult completeBlock(PreparedEdit edit, String answerText, UUID turn, boolean stopped) {
        if (stopped || turn == null) return result(edit, DocumentEditResult.Status.FAILED, null);
        String block = lastFencedBlock(answerText, edit.lang());
        if (block == null || ChatDocumentService.blockSha256(block).equals(edit.baseSha256())) {
            return result(edit, DocumentEditResult.Status.UNCHANGED, null);
        }
        ChatDocumentVersionEntity v = docs.addBlockVersion(edit.documentId(), edit.baseVersion(), turn, block);
        return result(edit, DocumentEditResult.Status.UPDATED, v.getVersion());
    }

    private DocumentEditResult completeFile(PreparedEdit edit, UUID turn, boolean stopped) throws IOException {
        Path file = docs.outFile(edit.sessionId(), edit.name());
        if (stopped || turn == null || file == null || !Files.isRegularFile(file)) {
            restoreLatest(edit);
            return result(edit, DocumentEditResult.Status.FAILED, null);
        }
        if (ChatDocumentService.sha256(file).equals(edit.baseSha256())) {
            restoreLatest(edit);
            return result(edit, DocumentEditResult.Status.UNCHANGED, null);
        }
        ChatDocumentService.StoredFile stored = docs.storeFile(edit.name(), file,
                docs.sourceFile(edit.sessionId(), edit.name()));
        ChatDocumentVersionEntity v = docs.addFileVersion(edit.documentId(), edit.baseVersion(), turn, stored);
        return result(edit, DocumentEditResult.Status.UPDATED, v.getVersion());
    }

    private void restoreLatest(PreparedEdit edit) {
        if (edit.kind() != ChatDocumentEntity.Kind.FILE) return;
        docs.find(edit.documentId()).ifPresent(docs::restoreLatest);
    }

    private ChatDocumentVersionEntity baseVersion(ChatDocumentEntity doc, Integer requested) {
        int version = requested != null ? requested : doc.getLatestVersion();
        return docs.liveVersion(doc.getId(), version)
                .orElseThrow(() -> new ChatRejectedException(HttpStatus.BAD_REQUEST, "document_edit_invalid:base_version"));
    }

    /** BIZ-203: 원본 없는 파일은 실행기 연결만 고칠 수 있다. 연결 묶음은 모든 멤버가 실행기일 때만. */
    boolean binaryEditSupported(String connectionId, String connectionGroupId) {
        if (connectionGroupId != null && !connectionGroupId.isBlank()) {
            List<String> members = connectionGroupSelector.memberConnectionIds(connectionGroupId);
            return !members.isEmpty() && members.stream().allMatch(connectionAdapterFactory::isCliConnection);
        }
        return widgetSettings.binaryDocumentEditSupported(connectionId);
    }

    /**
     * PRD-484, BIZ-204: 그 판 내용을 마지막 판 다음 번호로 복사한다. 대화를 거치지 않는다.
     * FILE 은 out/ 의 파일과 원본을 새 판으로 맞춘다. 작업 폴더가 정리되어 없으면 다음 고치기 때 맞춘다.
     *
     * @param currentUser 요청한 사용자. 세션에 사용자가 있으면 같아야 한다(CR-046 세션 삭제와 같은 본인 확인)
     * @throws ChatRejectedException 404 document_not_found, 403 document_forbidden, 409 session_busy
     */
    public DocumentEditResult restore(UUID documentId, int version, String currentUser) {
        ChatDocumentEntity doc = docs.find(documentId).orElseThrow(DocumentEditService::notFound);
        docs.liveVersion(documentId, version).orElseThrow(DocumentEditService::notFound);
        String owner = sessionRepository.findBySessionId(doc.getSessionId())
                .map(ConversationSessionEntity::getUserId).orElse(null);
        if (owner != null && !owner.isBlank() && currentUser != null && !owner.equals(currentUser)) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, "document_forbidden");
        }
        if (cancellationRegistry.isActive(doc.getSessionId())) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "session_busy");
        }
        ChatDocumentVersionEntity made = docs.restoreVersion(documentId, version);
        if (made == null) return new DocumentEditResult(documentId, DocumentEditResult.Status.UNCHANGED, null);
        docs.find(documentId).ifPresent(docs::restoreLatest);
        return new DocumentEditResult(documentId, DocumentEditResult.Status.UPDATED, made.getVersion());
    }

    /** 선택한 부분이 있으면 selection 틀을 채운 글, 없으면 빈 글. 종류별 틀의 {{selection}} 자리에 들어간다. */
    private String selectionPart(DocumentEditRequest request) {
        String text = request.selection() != null ? request.selection().text() : null;
        if (text == null || text.isBlank()) return "";
        return render(TEMPLATE_SELECTION, Map.of("text", text));
    }

    private String render(String key, Map<String, Object> vars) {
        String text = promptTemplateService.render(key, vars);
        if (text == null) throw new IllegalStateException("프롬프트 틀 없음: " + key);
        return text;
    }

    private static DocumentEditResult result(PreparedEdit edit, DocumentEditResult.Status status, Integer version) {
        return new DocumentEditResult(edit.documentId(), status, version);
    }

    /** out/ 바로 아래 파일 이름만 받는다. 숨김 파일(원본 폴더 .source 등)은 고칠 문서가 아니다. */
    static boolean isPlainFileName(String name) {
        return name != null && !name.isBlank() && !name.startsWith(".")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    /**
     * 답에서 그 언어로 표시한 마지막 코드 블록 본문. 닫히지 않은 블록(중간에 끊긴 답)은 보지 않는다.
     * 펜스는 CommonMark 와 같게 백틱이나 물결 3개 이상, 닫는 펜스는 같은 문자로 여는 것 이상 길이다.
     */
    static String lastFencedBlock(String text, String lang) {
        if (text == null || lang == null) return null;
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        String found = null;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();
            char c = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
            int n = 0;
            while (n < trimmed.length() && trimmed.charAt(n) == c) n++;
            boolean opens = indent <= 3 && (c == '`' || c == '~') && n >= 3
                    && !(c == '`' && trimmed.substring(n).indexOf('`') >= 0);
            if (!opens) {
                i++;
                continue;
            }
            String info = trimmed.substring(n).strip();
            String infoLang = info.isEmpty() ? "" : info.split("\\s+")[0].toLowerCase(Locale.ROOT);
            List<String> body = new ArrayList<>();
            int j = i + 1;
            boolean closed = false;
            for (; j < lines.length; j++) {
                if (isClosingFence(lines[j], c, n)) {
                    closed = true;
                    break;
                }
                body.add(lines[j]);
            }
            if (!closed) break;
            if (infoLang.equals(lang)) found = String.join("\n", body);
            i = j + 1;
        }
        return found;
    }

    private static boolean isClosingFence(String line, char c, int minLength) {
        String t = line.strip();
        if (t.length() < minLength) return false;
        for (int k = 0; k < t.length(); k++) {
            if (t.charAt(k) != c) return false;
        }
        return line.length() - line.stripLeading().length() <= 3;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ChatRejectedException invalidTarget() {
        return new ChatRejectedException(HttpStatus.BAD_REQUEST, "document_edit_invalid:target");
    }

    private static ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "document_not_found");
    }

    private static ChatRejectedException sourceGone() {
        return new ChatRejectedException(HttpStatus.GONE, "document_source_gone");
    }

    private static ChatRejectedException unsupported() {
        return new ChatRejectedException(HttpStatus.BAD_REQUEST, "document_edit_unsupported");
    }
}
