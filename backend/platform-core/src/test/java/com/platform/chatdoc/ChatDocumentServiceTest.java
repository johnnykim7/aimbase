package com.platform.chatdoc;

import com.platform.artifact.ArtifactService;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.repository.ChatDocumentRepository;
import com.platform.repository.ChatDocumentVersionRepository;
import com.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-181 T3-5 TC-CHT-070, 071, 073, 079. */
@ExtendWith(MockitoExtension.class)
class ChatDocumentServiceTest {

    @Mock ChatDocumentRepository documents;
    @Mock ChatDocumentVersionRepository versions;
    @Mock StorageService storage;
    @Mock ArtifactService artifactService;

    ChatDocumentService service;

    @BeforeEach
    void setUp() {
        service = new ChatDocumentService(documents, versions, storage, artifactService);
    }

    @Test
    void createBlock_makesOriginalVersionOne() {
        UUID turn = UUID.randomUUID();
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDocumentEntity d = service.createBlock("s1", turn, 2, "html", "<p>a</p>");

        assertThat(d.getLatestVersion()).isEqualTo(1);
        assertThat(d.getKind()).isEqualTo(ChatDocumentEntity.Kind.BLOCK);
        ArgumentCaptor<ChatDocumentVersionEntity> v = ArgumentCaptor.forClass(ChatDocumentVersionEntity.class);
        verify(versions).save(v.capture());
        assertThat(v.getValue().getVersion()).isEqualTo(1);
        assertThat(v.getValue().getMadeBy()).isEqualTo(ChatDocumentVersionEntity.MadeBy.ORIGINAL);
        assertThat(v.getValue().getBaseVersion()).isNull();
        assertThat(v.getValue().getTurnId()).isEqualTo(turn);
        assertThat(v.getValue().getContent()).isEqualTo("<p>a</p>");
    }

    @Test
    void addBlockVersion_numbersAfterLiveMaxEvenFromOlderBase() {
        ChatDocumentEntity d = blockDoc(3);
        when(documents.findForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(versions.findLiveMaxVersion(d.getId())).thenReturn(3);
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDocumentVersionEntity v = service.addBlockVersion(d.getId(), 1, UUID.randomUUID(), "<p>b</p>");

        assertThat(v.getVersion()).isEqualTo(4);
        assertThat(v.getBaseVersion()).isEqualTo(1);
        assertThat(v.getMadeBy()).isEqualTo(ChatDocumentVersionEntity.MadeBy.CHAT_EDIT);
        assertThat(d.getLatestVersion()).isEqualTo(4);
    }

    @Test
    void restoreVersion_block_copiesContentAsNextVersion() {
        ChatDocumentEntity d = blockDoc(3);
        ChatDocumentVersionEntity v1 = blockVersion(d.getId(), 1, "<p>a</p>");
        ChatDocumentVersionEntity v3 = blockVersion(d.getId(), 3, "<p>c</p>");
        when(documents.findForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(versions.findLive(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(versions.findLive(d.getId(), 3)).thenReturn(Optional.of(v3));
        when(versions.findLiveMaxVersion(d.getId())).thenReturn(3);
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDocumentVersionEntity made = service.restoreVersion(d.getId(), 1);

        assertThat(made.getVersion()).isEqualTo(4);
        assertThat(made.getBaseVersion()).isEqualTo(1);
        assertThat(made.getMadeBy()).isEqualTo(ChatDocumentVersionEntity.MadeBy.RESTORE);
        assertThat(made.getTurnId()).isNull();
        assertThat(made.getContent()).isEqualTo("<p>a</p>");
        assertThat(d.getLatestVersion()).isEqualTo(4);
    }

    @Test
    void restoreVersion_sameAsLatest_returnsNullWithoutSaving() {
        ChatDocumentEntity d = blockDoc(2);
        ChatDocumentVersionEntity v1 = blockVersion(d.getId(), 1, "<p>a</p>");
        ChatDocumentVersionEntity v2 = blockVersion(d.getId(), 2, "<p>a</p>\n");
        when(documents.findForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(versions.findLive(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(versions.findLive(d.getId(), 2)).thenReturn(Optional.of(v2));

        assertThat(service.restoreVersion(d.getId(), 1)).isNull();
        verify(versions, never()).save(any());
        assertThat(d.getLatestVersion()).isEqualTo(2);
    }

    @Test
    void restoreVersion_file_pointsAtSameStoredFile() {
        ChatDocumentEntity d = fileDoc("a.pptx", 2);
        ChatDocumentVersionEntity v1 = fileVersion(d.getId(), 1, "p1", "s1", "sha1");
        ChatDocumentVersionEntity v2 = fileVersion(d.getId(), 2, "p2", null, "sha2");
        when(documents.findForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(versions.findLive(d.getId(), 1)).thenReturn(Optional.of(v1));
        when(versions.findLive(d.getId(), 2)).thenReturn(Optional.of(v2));
        when(versions.findLiveMaxVersion(d.getId())).thenReturn(2);
        when(versions.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDocumentVersionEntity made = service.restoreVersion(d.getId(), 1);

        assertThat(made.getVersion()).isEqualTo(3);
        assertThat(made.getStoragePath()).isEqualTo("p1");
        assertThat(made.getSourceStoragePath()).isEqualTo("s1");
        assertThat(made.getSha256()).isEqualTo("sha1");
        assertThat(made.getMadeBy()).isEqualTo(ChatDocumentVersionEntity.MadeBy.RESTORE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteEditsOfTurns_softDeletesAndRecountsLatest() {
        UUID turn = UUID.randomUUID();
        ChatDocumentEntity d = blockDoc(3);
        ChatDocumentVersionEntity v3 = blockVersion(d.getId(), 3, "<p>c</p>");
        v3.setTurnId(turn);
        OffsetDateTime ts = OffsetDateTime.now();
        when(versions.findLiveEditsByTurnIds(List.of(turn))).thenReturn(List.of(v3));
        when(documents.findForUpdate(d.getId())).thenReturn(Optional.of(d));
        when(versions.findLiveMaxVersion(d.getId())).thenReturn(2);

        Set<UUID> hit = service.deleteEditsOfTurns(List.of(turn), ts);

        assertThat(hit).containsExactly(d.getId());
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(versions).softDelete(ids.capture(), org.mockito.ArgumentMatchers.eq(ts));
        assertThat(ids.getValue()).hasSize(1);
        assertThat(d.getLatestVersion()).isEqualTo(2);
    }

    @Test
    void deleteEditsOfTurns_noEdits_doesNothing() {
        UUID turn = UUID.randomUUID();
        when(versions.findLiveEditsByTurnIds(List.of(turn))).thenReturn(List.of());

        assertThat(service.deleteEditsOfTurns(List.of(turn), OffsetDateTime.now())).isEmpty();
        verify(versions, never()).softDelete(any(), any());
    }

    @Test
    void writeToOutDir_copiesVersionAndRemovesStaleSource(@TempDir Path outDir) throws Exception {
        ChatDocumentEntity d = fileDoc("a.pptx", 1);
        byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
        ChatDocumentVersionEntity v = fileVersion(d.getId(), 1, "p1", null, ChatDocumentService.sha256(bytes));
        Files.writeString(outDir.resolve("a.pptx"), "old");
        Path source = outDir.resolve(".source").resolve("a.pptx.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "{}");
        when(artifactService.resolveOutDir("s1")).thenReturn(outDir);
        when(storage.load("p1")).thenReturn(new ByteArrayInputStream(bytes));

        service.writeToOutDir(d, v);

        assertThat(Files.readString(outDir.resolve("a.pptx"))).isEqualTo("new");
        assertThat(source).doesNotExist();
    }

    @Test
    void writeToOutDir_sameContent_doesNotLoadStorage(@TempDir Path outDir) throws Exception {
        ChatDocumentEntity d = fileDoc("a.pptx", 1);
        Files.writeString(outDir.resolve("a.pptx"), "same");
        ChatDocumentVersionEntity v = fileVersion(d.getId(), 1, "p1", null,
                ChatDocumentService.sha256("same".getBytes(StandardCharsets.UTF_8)));
        when(artifactService.resolveOutDir("s1")).thenReturn(outDir);

        service.writeToOutDir(d, v);

        verify(storage, never()).load(any());
    }

    @Test
    void outFile_rejectsPathEscape(@TempDir Path outDir) {
        when(artifactService.resolveOutDir("s1")).thenReturn(outDir);

        assertThatThrownBy(() -> service.outFile("s1", "../x.pptx")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockSha256_ignoresSurroundingWhitespace() {
        assertThat(ChatDocumentService.blockSha256("<p>a</p>\n\n"))
                .isEqualTo(ChatDocumentService.blockSha256("<p>a</p>"));
        assertThat(ChatDocumentService.blockSha256("<p>a</p>"))
                .isNotEqualTo(ChatDocumentService.blockSha256("<p>b</p>"));
    }

    @Test
    void listForSession_includesMadeBy() {
        ChatDocumentEntity d = blockDoc(2);
        ChatDocumentVersionEntity v1 = blockVersion(d.getId(), 1, "<p>a</p>");
        v1.setMadeBy(ChatDocumentVersionEntity.MadeBy.ORIGINAL);
        ChatDocumentVersionEntity v2 = blockVersion(d.getId(), 2, "<p>a</p>");
        v2.setMadeBy(ChatDocumentVersionEntity.MadeBy.RESTORE);
        v2.setBaseVersion(1);
        when(documents.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(d));
        when(versions.findLiveByDocumentIds(List.of(d.getId()))).thenReturn(List.of(v1, v2));

        List<Map<String, Object>> out = service.listForSession("s1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vs = (List<Map<String, Object>>) out.get(0).get("versions");
        assertThat(vs).extracting(m -> m.get("made_by")).containsExactly("ORIGINAL", "RESTORE");
        assertThat(vs.get(1).get("base_version")).isEqualTo(1);
        assertThat(out.get(0).get("latest_version")).isEqualTo(2);
    }

    static ChatDocumentEntity blockDoc(int latest) {
        ChatDocumentEntity d = new ChatDocumentEntity();
        d.setId(UUID.randomUUID());
        d.setSessionId("s1");
        d.setKind(ChatDocumentEntity.Kind.BLOCK);
        d.setLang("html");
        d.setLatestVersion(latest);
        return d;
    }

    static ChatDocumentEntity fileDoc(String name, int latest) {
        ChatDocumentEntity d = new ChatDocumentEntity();
        d.setId(UUID.randomUUID());
        d.setSessionId("s1");
        d.setKind(ChatDocumentEntity.Kind.FILE);
        d.setName(name);
        d.setLatestVersion(latest);
        return d;
    }

    static ChatDocumentVersionEntity blockVersion(UUID docId, int version, String content) {
        ChatDocumentVersionEntity v = new ChatDocumentVersionEntity();
        v.setDocumentId(docId);
        v.setVersion(version);
        v.setMadeBy(ChatDocumentVersionEntity.MadeBy.CHAT_EDIT);
        v.setContent(content);
        v.setSizeBytes(content.length());
        v.setSha256(ChatDocumentService.blockSha256(content));
        return v;
    }

    static ChatDocumentVersionEntity fileVersion(UUID docId, int version, String path, String sourcePath, String sha) {
        ChatDocumentVersionEntity v = new ChatDocumentVersionEntity();
        v.setDocumentId(docId);
        v.setVersion(version);
        v.setMadeBy(ChatDocumentVersionEntity.MadeBy.CHAT_EDIT);
        v.setStoragePath(path);
        v.setSourceStoragePath(sourcePath);
        v.setSizeBytes(10);
        v.setSha256(sha);
        return v;
    }
}
