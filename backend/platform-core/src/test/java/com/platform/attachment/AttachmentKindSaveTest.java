package com.platform.attachment;

import com.platform.attachment.data.DataFiles;
import com.platform.attachment.data.DataTableReader;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** CR-183 TC-CHT-090(종류 판정), TC-CHT-091(크기, 깨진 파일) 올리기 경로. */
class AttachmentKindSaveTest {

    private PlatformSettingsService settings;
    private ChatAttachmentRepository repo;
    private AttachmentService service;

    @BeforeEach
    void setUp() throws Exception {
        settings = mock(PlatformSettingsService.class);
        repo = mock(ChatAttachmentRepository.class);
        StorageService storage = mock(StorageService.class);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(repo.save(any(ChatAttachmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storage.save(anyString(), anyString(), anyString(), any())).thenReturn("p");
        service = new AttachmentService(repo, storage, new MimeValidator(), settings, new DataTableReader(settings));
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    static Stream<Arguments> kinds() {
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("a.xlsx", DataFiles.xlsxRows("s", new Object[]{"a", "b"}, 2), AttachmentKind.XLSX),
                Arguments.of("a.xls", DataFiles.xls(wb -> DataFiles.addSheet(wb, "s", new Object[]{"a", "b"}, 2)),
                        AttachmentKind.XLS),
                Arguments.of("a.csv", csv, AttachmentKind.CSV),
                Arguments.of("b.csv", "지점,매출\n강남,1\n".getBytes(Charset.forName("MS949")), AttachmentKind.CSV),
                Arguments.of("a.TSV", "a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8), AttachmentKind.TSV),
                Arguments.of("a.docx", DataFiles.docx(), AttachmentKind.DOCX),
                Arguments.of("a.txt", "본문".getBytes(StandardCharsets.UTF_8), AttachmentKind.TEXT),
                Arguments.of("a.md", "# 제목".getBytes(StandardCharsets.UTF_8), AttachmentKind.MARKDOWN));
    }

    @ParameterizedTest
    @MethodSource("kinds")
    @DisplayName("확장자로 종류를 정하고 Content-Type 은 보지 않는다")
    void save_setsMediaTypeByExtension(String name, byte[] bytes, AttachmentKind kind) {
        ChatAttachmentEntity saved = service.save("s1", new MockMultipartFile("file", name,
                "application/octet-stream", bytes));
        assertThat(saved.getMediaType()).isEqualTo(kind.mediaType());
    }

    @Test
    @DisplayName("이름만 xlsx 인 PNG, NUL 바이트가 있는 txt 는 422 FG-ATT-4221")
    void contentMismatch() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertCode("a.xlsx", png, AttachmentException.CODE_MIME_MISMATCH);
        assertCode("a.txt", new byte[]{'a', 0, 'b'}, AttachmentException.CODE_MIME_MISMATCH);
    }

    @Test
    @DisplayName("확장자 없는 파일, hwp 는 400 FG-ATT-4001")
    void unsupported() {
        byte[] bytes = "plain text body".getBytes(StandardCharsets.UTF_8);
        assertCode("noext", bytes, AttachmentException.CODE_MIME_UNSUPPORTED);
        assertCode("a.hwp", bytes, AttachmentException.CODE_MIME_UNSUPPORTED);
    }

    @Test
    @DisplayName("깨진 docx 는 FG-ATT-4004, 크기 상한 + 1 은 FG-ATT-4002 와 상한 값")
    void unreadableAndSize() {
        assertCode("a.docx", DataFiles.brokenDocx(), AttachmentException.CODE_UNREADABLE);

        lenient().when(settings.getInt(eq(AttachmentKind.MAX_DATA_BYTES_KEY), anyInt())).thenReturn(7);
        assertThatThrownBy(() -> service.save("s1", new MockMultipartFile("file", "a.csv", "text/csv",
                "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(AttachmentException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AttachmentException.CODE_SIZE_EXCEEDED);
                    assertThat(e.getLimit()).isEqualTo(7L);
                });
    }

    private void assertCode(String name, byte[] bytes, String code) {
        assertThatThrownBy(() -> service.save("s1", new MockMultipartFile("file", name, "text/plain", bytes)))
                .isInstanceOf(AttachmentException.class)
                .extracting("code").isEqualTo(code);
    }
}
