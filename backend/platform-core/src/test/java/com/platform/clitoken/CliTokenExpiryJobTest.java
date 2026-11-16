package com.platform.clitoken;

import com.platform.config.PlatformSettingsService;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-196 TC-TOK-010, 013, 014, 024. */
class CliTokenExpiryJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private static final List<Integer> T = List.of(30, 7, 1, 0);

    private CliAccountTokenService tokens;
    private CliTokenRecipientService recipients;
    private CliAccountTokenStore store;
    private CliTokenNotifier notifier;
    private PlatformSettingsService settings;
    private CliTokenExpiryJob job;

    @BeforeEach
    void setUp() {
        tokens = mock(CliAccountTokenService.class);
        recipients = mock(CliTokenRecipientService.class);
        store = mock(CliAccountTokenStore.class);
        notifier = mock(CliTokenNotifier.class);
        settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(eq(CliTokenExpiryJob.KEY_NOTIFY_DAYS), any())).thenReturn("30,7,1,0");
        when(notifier.sendEmail(any(), any(), any())).thenReturn(true);
        when(notifier.sendSms(any(), any(), any())).thenReturn(true);
        job = new CliTokenExpiryJob(mock(TenantDataSourceManager.class), tokens, recipients, store, notifier,
                settings, CLOCK);
    }

    @Test
    void tc_tok_010_pickThreshold() {
        assertThat(CliTokenExpiryJob.pickThreshold(40, T)).isNull();
        assertThat(CliTokenExpiryJob.pickThreshold(30, T)).isEqualTo(30);
        assertThat(CliTokenExpiryJob.pickThreshold(5, T)).isEqualTo(7);
        assertThat(CliTokenExpiryJob.pickThreshold(1, T)).isEqualTo(1);
        assertThat(CliTokenExpiryJob.pickThreshold(0, T)).isEqualTo(0);
        assertThat(CliTokenExpiryJob.pickThreshold(-3, T)).isEqualTo(0);
        assertThat(CliTokenExpiryJob.parseThresholds(" 30, 7 ,x,-1,0")).containsExactly(30, 7, 0);
    }

    @Test
    void tc_tok_010_alreadySentThresholdIsNotSentAgain() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(row("tenant", 5)));
        when(recipients.get()).thenReturn(new Recipients(List.of("a@x.com"), List.of()));
        when(store.noticeSent(eq("t1"), eq("runner-a"), eq("/data/work01"), any(), eq(7))).thenReturn(true);

        job.checkCurrentTenant("t1");

        verify(notifier, never()).sendEmail(any(), any(), any());
        verify(store, never()).recordNotice(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void tc_tok_013_emailOnlyRecordsEmailAndEmptyRecipientsSendNothing() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(row("tenant", 7)));
        when(recipients.get()).thenReturn(new Recipients(List.of("a@x.com"), List.of()));

        job.checkCurrentTenant("t1");

        verify(notifier).sendEmail(eq("AIMBASE_CLI_TOKEN_EXPIRY_EMAIL"),
                eq(Map.of("runner", "runner-a", "account", "work01", "expiresOn", "2026-11-23", "daysLeft", 7L)),
                eq("a@x.com"));
        verify(notifier, never()).sendSms(any(), any(), any());
        verify(store).recordNotice(eq("t1"), eq("runner-a"), eq("/data/work01"), any(), eq(7), eq("email"), any());

    }

    @Test
    void tc_tok_013_noRecipientsSendsNothingAndRecordsNothing() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(row("tenant", 7)));
        when(recipients.get()).thenReturn(new Recipients(List.of(), List.of()));

        job.checkCurrentTenant("t1");

        verify(notifier, never()).sendEmail(any(), any(), any());
        verify(notifier, never()).sendSms(any(), any(), any());
        verify(store, never()).recordNotice(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void tc_tok_014_sendFailureLeavesNoRecord() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(row("tenant", 1)));
        when(recipients.get()).thenReturn(new Recipients(List.of("a@x.com"), List.of("01012345678")));
        when(notifier.sendSms(any(), any(), any())).thenReturn(false);

        job.checkCurrentTenant("t1");

        verify(notifier).sendEmail(any(), any(), eq("a@x.com"));
        verify(store, never()).recordNotice(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void tc_tok_024_recipientsSplitByManagedBy() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(row("platform", 30), row("tenant", 0)));
        when(recipients.get()).thenReturn(new Recipients(List.of("owner@t1.com"), List.of()));
        when(settings.getString(eq(CliTokenExpiryJob.KEY_EMAILS), any())).thenReturn("ops@aimbase.io");
        when(settings.getString(eq(CliTokenExpiryJob.KEY_PHONES), any())).thenReturn("010-9999-8888");

        job.checkCurrentTenant("t1");

        verify(notifier).sendEmail(any(), any(), eq("ops@aimbase.io"));
        verify(notifier).sendSms(any(), any(), eq("01099998888"));
        verify(notifier).sendEmail(any(), any(), eq("owner@t1.com"));
        verify(store).recordNotice(eq("t1"), any(), any(), any(), eq(30), eq("email,sms"), any());
        verify(store).recordNotice(eq("t1"), any(), any(), any(), eq(0), eq("email"), any());
    }

    @Test
    void tc_tok_013_missingTokenIsNotNotified() {
        when(tokens.listCurrentTenant()).thenReturn(List.of(new TokenRow(null, null, "runner-a", "/data/work01",
                "tenant", false, null, null, null, true, List.of("c1"), null)));

        job.checkCurrentTenant("t1");

        verify(recipients, never()).get();
        verify(notifier, never()).sendEmail(any(), any(), any());
    }

    private static TokenRow row(String managedBy, long daysLeft) {
        OffsetDateTime expires = OffsetDateTime.parse("2026-11-16T10:00+09:00").plusDays(daysLeft);
        return new TokenRow(null, null, "runner-a", "/data/work01", managedBy, true,
                expires.minusDays(365).toString(), expires.toString(), daysLeft, true, List.of("c1"), null);
    }
}
