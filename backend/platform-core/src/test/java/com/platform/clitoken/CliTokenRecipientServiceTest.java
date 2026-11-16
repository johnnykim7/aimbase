package com.platform.clitoken;

import com.platform.domain.CliTokenRecipientEntity;
import com.platform.repository.CliTokenRecipientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-196 TC-TOK-023. */
class CliTokenRecipientServiceTest {

    private final List<CliTokenRecipientEntity> rows = new ArrayList<>();
    private CliTokenRecipientService service;

    @BeforeEach
    void setUp() {
        CliTokenRecipientRepository repo = mock(CliTokenRecipientRepository.class);
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(rows));
        doAnswer(i -> { rows.clear(); return null; }).when(repo).deleteAllInBatch();
        when(repo.saveAll(anyList())).thenAnswer(i -> { rows.addAll(i.getArgument(0)); return i.getArgument(0); });
        service = new CliTokenRecipientService(repo);
    }

    @Test
    void tc_tok_023_putThenGetReturnsSameValues() {
        service.replace(new Recipients(List.of("a@x.com", " b@y.co.kr "), List.of("010-1234-5678")));

        Recipients got = service.get();

        assertThat(got.emails()).containsExactly("a@x.com", "b@y.co.kr");
        assertThat(got.phones()).containsExactly("01012345678");
    }

    @Test
    void tc_tok_023_sameValueTwiceIsOneRow() {
        service.replace(new Recipients(List.of("a@x.com", "a@x.com"), List.of("01012345678", "010-1234-5678")));

        assertThat(rows).hasSize(2);
    }

    @Test
    void tc_tok_023_wrongShapeKeepsPreviousValues() {
        service.replace(new Recipients(List.of("a@x.com"), List.of()));

        assertThatThrownBy(() -> service.replace(new Recipients(List.of("a@x.com", "not-mail"), List.of())))
                .isInstanceOf(CliTokenApiException.class).hasMessage("recipient_invalid:not-mail")
                .satisfies(e -> assertThat(((CliTokenApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.replace(new Recipients(List.of(), List.of("02-123-4567"))))
                .hasMessage("recipient_invalid:02-123-4567");

        assertThat(service.get().emails()).containsExactly("a@x.com");
    }
}
