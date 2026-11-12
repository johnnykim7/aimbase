package com.platform.chatdoc;

import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.orchestrator.FileCreateToolGate;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.service.PromptTemplateService;
import com.platform.session.CancellationRegistry;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatWidgetSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-181 T3-5 TC-CHT-070 ~ 077. */
@ExtendWith(MockitoExtension.class)
class DocumentEditServiceTest {

    @Mock ChatDocumentService docs;
    @Mock ConversationMessageRepository messageRepository;
    @Mock ConversationSessionRepository sessionRepository;
    @Mock CancellationRegistry cancellationRegistry;
    @Mock PromptTemplateService promptTemplateService;
    @Mock ChatWidgetSettingsService widgetSettings;
    @Mock ConnectionAdapterFactory connectionAdapterFactory;
    @Mock ConnectionGroupSelector connectionGroupSelector;
    @Mock FileCreateToolGate fileCreateGate;

    DocumentEditService service;

    @BeforeEach
    void setUp() {
        service = new DocumentEditService(docs, messageRepository, sessionRepository, cancellationRegistry,
                promptTemplateService, widgetSettings, connectionAdapterFactory, connectionGroupSelector, fileCreateGate);
    }

    @Test
    void prepare_withoutSession_isRejected() {
        assertRejected(() -> service.prepare(" ", blockRequest(UUID.randomUUID(), null), null, null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:no_session");
    }

    @Test
    void prepare_withoutTarget_isRejected() {
        assertRejected(() -> service.prepare("s1", new DocumentEditRequest(null, null, null), null, null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:target");
    }

    @Test
    void prepare_blockWithUnsupportedLang_isRejected() {
        DocumentEditRequest req = new DocumentEditRequest(
                new DocumentEditRequest.Target("block", null, UUID.randomUUID().toString(), 0, "python", "x"),
                null, null);
        assertRejected(() -> service.prepare("s1", req, null, null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:target");
    }

    @Test
    void prepare_whileAnswering_isBusy() {
        when(cancellationRegistry.isActive("s1")).thenReturn(true);
        assertRejected(() -> service.prepare("s1", blockRequest(UUID.randomUUID(), null), null, null),
                HttpStatus.CONFLICT, "session_busy");
    }

    @Test
    void prepare_blockOfAnotherSessionTurn_isNotFound() {
        UUID turn = UUID.randomUUID();
        when(docs.findBlock("s1", turn, 0)).thenReturn(Optional.empty());
        when(messageRepository.findBySessionIdAndTurnId("s1", turn)).thenReturn(List.of());

        assertRejected(() -> service.prepare("s1", blockRequest(turn, null), null, null),
                HttpStatus.NOT_FOUND, "document_not_found");
        verify(docs, never()).createBlock(any(), any(), anyInt(), any(), any());
    }

    @Test
    void prepare_firstBlockEdit_createsDocumentAndEmptySelection() {
        UUID turn = UUID.randomUUID();
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(1);
        ChatDocumentVersionEntity v1 = ChatDocumentServiceTest.blockVersion(d.getId(), 1, "<p>a</p>");
        when(docs.findBlock("s1", turn, 0)).thenReturn(Optional.empty());
        when(messageRepository.findBySessionIdAndTurnId("s1", turn)).thenReturn(List.of(new ConversationMessageEntity()));
        when(docs.createBlock("s1", turn, 0, "html", "<p>a</p>")).thenReturn(d);
        when(docs.liveVersion(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(promptTemplateService.render(eq(DocumentEditService.TEMPLATE_BLOCK), anyMap())).thenReturn("EDIT");

        PreparedEdit edit = service.prepare("s1", blockRequest(turn, null), null, null);

        assertThat(edit.documentId()).isEqualTo(d.getId());
        assertThat(edit.baseVersion()).isEqualTo(1);
        assertThat(edit.instruction()).isEqualTo("EDIT");
        Map<String, Object> vars = blockVars();
        assertThat(vars).containsEntry("content", "<p>a</p>").containsEntry("selection", "");
        verify(promptTemplateService, never()).render(eq(DocumentEditService.TEMPLATE_SELECTION), anyMap());
    }

    @Test
    void prepare_withSelection_fillsSelectionSlot() {
        UUID turn = UUID.randomUUID();
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(1);
        ChatDocumentVersionEntity v1 = ChatDocumentServiceTest.blockVersion(d.getId(), 1, "<p>a</p>");
        when(docs.findBlock("s1", turn, 0)).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(promptTemplateService.render(eq(DocumentEditService.TEMPLATE_SELECTION), anyMap())).thenReturn("SEL\n\n");
        when(promptTemplateService.render(eq(DocumentEditService.TEMPLATE_BLOCK), anyMap())).thenReturn("EDIT");

        service.prepare("s1", blockRequest(turn, "a"), null, null);

        assertThat(blockVars()).containsEntry("selection", "SEL\n\n");
    }

    @Test
    void prepare_missingBaseVersion_isRejected() {
        UUID turn = UUID.randomUUID();
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(2);
        when(docs.findBlock("s1", turn, 0)).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 2)).thenReturn(Optional.empty());

        assertRejected(() -> service.prepare("s1", blockRequest(turn, null), null, null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:base_version");
    }

    @Test
    void prepare_fileWithoutSourceOnApiConnection_isUnsupported(@TempDir Path outDir) throws Exception {
        Path file = outDir.resolve("a.pptx");
        Files.writeString(file, "x");
        when(docs.outFile("s1", "a.pptx")).thenReturn(file);
        when(docs.findFile("s1", "a.pptx")).thenReturn(Optional.empty());
        when(docs.sourceFile("s1", "a.pptx")).thenReturn(outDir.resolve(".source").resolve("a.pptx.json"));
        when(widgetSettings.binaryDocumentEditSupported("c1")).thenReturn(false);

        assertRejected(() -> service.prepare("s1", fileRequest("a.pptx"), "c1", null),
                HttpStatus.BAD_REQUEST, "document_edit_unsupported");
    }

    @Test
    void prepare_fileWithSource_givesFileCreateTool() {
        ChatDocumentEntity d = ChatDocumentServiceTest.fileDoc("a.docx", 1);
        ChatDocumentVersionEntity v1 = ChatDocumentServiceTest.fileVersion(d.getId(), 1, "p/a.docx", "p/a.docx.json", "sha1");
        when(docs.outFile("s1", "a.docx")).thenReturn(Path.of("out", "a.docx"));
        when(docs.findFile("s1", "a.docx")).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(fileCreateGate.enabled()).thenReturn(true);
        when(docs.readSource(v1)).thenReturn("{\"file_name\":\"a.docx\"}");
        when(promptTemplateService.render(eq(DocumentEditService.TEMPLATE_FILE_SOURCE), anyMap())).thenReturn("SRC");

        PreparedEdit edit = service.prepare("s1", fileRequest("a.docx"), "c1", null);

        assertThat(edit.fileCreate()).isTrue();
        assertThat(edit.instruction()).isEqualTo("SRC");
        verify(widgetSettings, never()).binaryDocumentEditSupported(any());
    }

    @Test
    void prepare_fileWithSourceWhileFileCreateOff_isUnsupported() {
        ChatDocumentEntity d = ChatDocumentServiceTest.fileDoc("a.docx", 1);
        ChatDocumentVersionEntity v1 = ChatDocumentServiceTest.fileVersion(d.getId(), 1, "p/a.docx", "p/a.docx.json", "sha1");
        when(docs.outFile("s1", "a.docx")).thenReturn(Path.of("out", "a.docx"));
        when(docs.findFile("s1", "a.docx")).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(fileCreateGate.enabled()).thenReturn(false);

        assertRejected(() -> service.prepare("s1", fileRequest("a.docx"), "c1", null),
                HttpStatus.BAD_REQUEST, "document_edit_unsupported");
    }

    @Test
    void prepare_fileGoneFromOutDir_isGone(@TempDir Path outDir) {
        when(docs.outFile("s1", "a.pptx")).thenReturn(outDir.resolve("a.pptx"));
        when(docs.findFile("s1", "a.pptx")).thenReturn(Optional.empty());

        assertRejected(() -> service.prepare("s1", fileRequest("a.pptx"), "c1", null),
                HttpStatus.GONE, "document_source_gone");
    }

    @Test
    void prepare_fileNameWithPath_isRejected() {
        assertRejected(() -> service.prepare("s1", fileRequest("../a.pptx"), "c1", null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:target");
        assertRejected(() -> service.prepare("s1", fileRequest(".source"), "c1", null),
                HttpStatus.BAD_REQUEST, "document_edit_invalid:target");
    }

    @Test
    void complete_blockWithChangedFence_addsVersion() {
        PreparedEdit edit = blockEdit("<p>a</p>");
        UUID turn = UUID.randomUUID();
        ChatDocumentVersionEntity v2 = ChatDocumentServiceTest.blockVersion(edit.documentId(), 2, "<p>b</p>");
        when(docs.addBlockVersion(edit.documentId(), 1, turn, "<p>b</p>")).thenReturn(v2);

        DocumentEditResult r = service.complete(edit, "here\n```html\n<p>b</p>\n```\ndone", turn.toString(), false);

        assertThat(r.status()).isEqualTo(DocumentEditResult.Status.UPDATED);
        assertThat(r.version()).isEqualTo(2);
        assertThat(service.complete(edit, "x", turn.toString(), false)).isNull();
    }

    @Test
    void complete_blockWithSameContentOrNoFence_isUnchanged() {
        UUID turn = UUID.randomUUID();
        assertThat(service.complete(blockEdit("<p>a</p>"), "```html\n<p>a</p>\n\n```", turn.toString(), false).status())
                .isEqualTo(DocumentEditResult.Status.UNCHANGED);
        assertThat(service.complete(blockEdit("<p>a</p>"), "no code here", turn.toString(), false).status())
                .isEqualTo(DocumentEditResult.Status.UNCHANGED);
        verify(docs, never()).addBlockVersion(any(), anyInt(), any(), any());
    }

    @Test
    void complete_stoppedOrUnsaved_isFailed() {
        assertThat(service.complete(blockEdit("<p>a</p>"), "```html\n<p>b</p>\n```", UUID.randomUUID().toString(), true)
                .status()).isEqualTo(DocumentEditResult.Status.FAILED);
        assertThat(service.complete(blockEdit("<p>a</p>"), "```html\n<p>b</p>\n```", null, false)
                .status()).isEqualTo(DocumentEditResult.Status.FAILED);
    }

    @Test
    void complete_fileUnchanged_restoresOutDir(@TempDir Path outDir) throws Exception {
        Path file = outDir.resolve("a.pptx");
        Files.writeString(file, "same");
        String sha = ChatDocumentService.sha256(file);
        ChatDocumentEntity d = ChatDocumentServiceTest.fileDoc("a.pptx", 1);
        PreparedEdit edit = new PreparedEdit(d.getId(), ChatDocumentEntity.Kind.FILE, "s1", "a.pptx", null, 1, sha, "I", false);
        when(docs.outFile("s1", "a.pptx")).thenReturn(file);
        when(docs.find(d.getId())).thenReturn(Optional.of(d));

        DocumentEditResult r = service.complete(edit, "ok", UUID.randomUUID().toString(), false);

        assertThat(r.status()).isEqualTo(DocumentEditResult.Status.UNCHANGED);
        verify(docs).restoreLatest(d);
        verify(docs, never()).addFileVersion(any(), anyInt(), any(), any());
    }

    @Test
    void complete_fileChanged_storesNextVersion(@TempDir Path outDir) throws Exception {
        Path file = outDir.resolve("a.pptx");
        Files.writeString(file, "changed");
        ChatDocumentEntity d = ChatDocumentServiceTest.fileDoc("a.pptx", 1);
        PreparedEdit edit = new PreparedEdit(d.getId(), ChatDocumentEntity.Kind.FILE, "s1", "a.pptx", null, 1, "old", "I", false);
        UUID turn = UUID.randomUUID();
        ChatDocumentService.StoredFile stored = new ChatDocumentService.StoredFile("p2", null, 7, "new");
        ChatDocumentVersionEntity v2 = ChatDocumentServiceTest.fileVersion(d.getId(), 2, "p2", null, "new");
        when(docs.outFile("s1", "a.pptx")).thenReturn(file);
        when(docs.sourceFile("s1", "a.pptx")).thenReturn(null);
        when(docs.storeFile("a.pptx", file, null)).thenReturn(stored);
        when(docs.addFileVersion(d.getId(), 1, turn, stored)).thenReturn(v2);

        DocumentEditResult r = service.complete(edit, "ok", turn.toString(), false);

        assertThat(r.status()).isEqualTo(DocumentEditResult.Status.UPDATED);
        assertThat(r.version()).isEqualTo(2);
    }

    @Test
    void restore_otherUsersSession_isForbidden() {
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(2);
        stubRestoreLookups(d, "alice");

        assertRejected(() -> service.restore(d.getId(), 1, "bob"), HttpStatus.FORBIDDEN, "document_forbidden");
        verify(docs, never()).restoreVersion(any(), anyInt());
    }

    @Test
    void restore_whileAnswering_isBusy() {
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(2);
        stubRestoreLookups(d, null);
        when(cancellationRegistry.isActive("s1")).thenReturn(true);

        assertRejected(() -> service.restore(d.getId(), 1, null), HttpStatus.CONFLICT, "session_busy");
    }

    @Test
    void restore_unknownVersion_isNotFound() {
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(2);
        when(docs.find(d.getId())).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 9)).thenReturn(Optional.empty());

        assertRejected(() -> service.restore(d.getId(), 9, null), HttpStatus.NOT_FOUND, "document_not_found");
    }

    @Test
    void restore_sameAsLatest_isUnchanged() {
        ChatDocumentEntity d = ChatDocumentServiceTest.blockDoc(2);
        stubRestoreLookups(d, "alice");
        when(docs.restoreVersion(d.getId(), 1)).thenReturn(null);

        DocumentEditResult r = service.restore(d.getId(), 1, "alice");

        assertThat(r.status()).isEqualTo(DocumentEditResult.Status.UNCHANGED);
        assertThat(r.version()).isNull();
    }

    @Test
    void restore_makesNextVersionAndAlignsOutDir() {
        ChatDocumentEntity d = ChatDocumentServiceTest.fileDoc("a.pptx", 2);
        stubRestoreLookups(d, null);
        ChatDocumentVersionEntity v3 = ChatDocumentServiceTest.fileVersion(d.getId(), 3, "p1", null, "sha1");
        when(docs.restoreVersion(d.getId(), 1)).thenReturn(v3);

        DocumentEditResult r = service.restore(d.getId(), 1, "anyone");

        assertThat(r.status()).isEqualTo(DocumentEditResult.Status.UPDATED);
        assertThat(r.version()).isEqualTo(3);
        verify(docs).restoreLatest(d);
    }

    @Test
    void binaryEditSupported_groupNeedsAllRunnerMembers() {
        when(connectionGroupSelector.memberConnectionIds("g1")).thenReturn(List.of("a", "b"));
        when(connectionAdapterFactory.isCliConnection("a")).thenReturn(true);
        when(connectionAdapterFactory.isCliConnection("b")).thenReturn(false);
        when(connectionGroupSelector.memberConnectionIds("g2")).thenReturn(List.of());

        assertThat(service.binaryEditSupported(null, "g1")).isFalse();
        assertThat(service.binaryEditSupported(null, "g2")).isFalse();
    }

    @Test
    void lastFencedBlock_takesLastClosedBlockOfThatLang() {
        String text = "```html\n<p>1</p>\n```\n```css\nb{}\n```\n~~~~html\n<p>2</p>\n```\n~~~~\n```html\n<p>cut";
        assertThat(DocumentEditService.lastFencedBlock(text, "html")).isEqualTo("<p>2</p>\n```");
        assertThat(DocumentEditService.lastFencedBlock("```svg\n<svg/>\n```", "html")).isNull();
        assertThat(DocumentEditService.lastFencedBlock("```HTML\r\n<p>x</p>\r\n```", "html")).isEqualTo("<p>x</p>");
    }

    private void stubRestoreLookups(ChatDocumentEntity d, String owner) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setSessionId("s1");
        s.setUserId(owner);
        when(docs.find(d.getId())).thenReturn(Optional.of(d));
        when(docs.liveVersion(d.getId(), 1)).thenReturn(Optional.of(new ChatDocumentVersionEntity()));
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(s));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> blockVars() {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(eq(DocumentEditService.TEMPLATE_BLOCK), vars.capture());
        return vars.getValue();
    }

    private static PreparedEdit blockEdit(String baseContent) {
        return new PreparedEdit(UUID.randomUUID(), ChatDocumentEntity.Kind.BLOCK, "s1", null, "html", 1,
                ChatDocumentService.blockSha256(baseContent), "I", false);
    }

    private static DocumentEditRequest blockRequest(UUID turn, String selection) {
        return new DocumentEditRequest(
                new DocumentEditRequest.Target("block", null, turn.toString(), 0, "html", "<p>a</p>"),
                null, selection != null ? new DocumentEditRequest.Selection(selection) : null);
    }

    private static DocumentEditRequest fileRequest(String name) {
        return new DocumentEditRequest(new DocumentEditRequest.Target("file", name, null, null, null, null), null, null);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(code);
                });
    }
}
