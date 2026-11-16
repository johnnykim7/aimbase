package com.platform.clitoken;

import com.platform.config.PlatformSettingsService;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-196 BIZ-284: 하루 한 번 테넌트마다 실행기 계정 폴더의 1년 토큰 기한을 확인하고, 알림 문턱에 든 폴더의 수신자에게
 * 메일과 문자를 보낸다. 같은 기한과 문턱에는 한 번만 보낸다.
 */
@Component
public class CliTokenExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CliTokenExpiryJob.class);

    static final String KEY_NOTIFY_DAYS = "runner.token_expiry.notify_days";
    static final String KEY_EMAILS = "runner.token_expiry.emails";
    static final String KEY_PHONES = "runner.token_expiry.phones";
    static final String KEY_EMAIL_TEMPLATE = "runner.token_expiry.email_template";
    static final String KEY_SMS_TEMPLATE = "runner.token_expiry.sms_template";

    private final TenantDataSourceManager tenantDataSources;
    private final CliAccountTokenService tokens;
    private final CliTokenRecipientService recipients;
    private final CliAccountTokenStore store;
    private final CliTokenNotifier notifier;
    private final PlatformSettingsService settings;
    private final Clock clock;

    @Autowired
    public CliTokenExpiryJob(TenantDataSourceManager tenantDataSources, CliAccountTokenService tokens,
                             CliTokenRecipientService recipients, CliAccountTokenStore store,
                             CliTokenNotifier notifier, PlatformSettingsService settings) {
        this(tenantDataSources, tokens, recipients, store, notifier, settings,
                Clock.system(CliAccountTokenService.ZONE));
    }

    CliTokenExpiryJob(TenantDataSourceManager tenantDataSources, CliAccountTokenService tokens,
                      CliTokenRecipientService recipients, CliAccountTokenStore store,
                      CliTokenNotifier notifier, PlatformSettingsService settings, Clock clock) {
        this.tenantDataSources = tenantDataSources;
        this.tokens = tokens;
        this.recipients = recipients;
        this.store = store;
        this.notifier = notifier;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "${aimbase.runner.token-expiry.cron:0 0 9 * * *}", zone = CliAccountTokenService.ZONE_ID)
    public void run() {
        for (String tenantId : tenantDataSources.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                checkCurrentTenant(tenantId);
            } catch (Exception e) {
                log.warn("[CR-196] 1년 토큰 만료 확인 실패: tenant={}, {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    void checkCurrentTenant(String tenantId) {
        List<TokenRow> rows = tokens.listCurrentTenant();
        if (rows.isEmpty()) return;
        List<Integer> thresholds = parseThresholds(settings.getString(KEY_NOTIFY_DAYS, "30,7,1,0"));
        Recipients tenantRecipients = null;
        Recipients platformRecipients = null;
        for (TokenRow row : rows) {
            if (!row.present() || row.expiresAt() == null || row.daysLeft() == null) continue;
            Integer threshold = pickThreshold(row.daysLeft(), thresholds);
            if (threshold == null) continue;
            OffsetDateTime expiresAt = OffsetDateTime.parse(row.expiresAt());
            if (store.noticeSent(tenantId, row.agentName(), row.configDir(), expiresAt, threshold)) continue;

            Recipients to;
            if (CliAccountTokenService.BY_PLATFORM.equals(row.managedBy())) {
                if (platformRecipients == null) platformRecipients = platformRecipients();
                to = platformRecipients;
            } else {
                if (tenantRecipients == null) tenantRecipients = recipients.get();
                to = tenantRecipients;
            }
            if (to.isEmpty()) {
                log.info("[CR-196] 만료 알림 수신자가 없어 보내지 않음: tenant={}, agent={}, dir={}, days_left={}",
                        tenantId, row.agentName(), row.configDir(), row.daysLeft());
                continue;
            }
            send(tenantId, row, expiresAt, threshold, to);
        }
    }

    private void send(String tenantId, TokenRow row, OffsetDateTime expiresAt, int threshold, Recipients to) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("runner", row.agentName());
        vars.put("account", accountName(row.configDir()));
        vars.put("expiresOn", expiresAt.atZoneSameInstant(CliAccountTokenService.ZONE).toLocalDate().toString());
        vars.put("daysLeft", row.daysLeft());

        boolean ok = true;
        List<String> channels = new ArrayList<>();
        if (!to.emails().isEmpty()) {
            channels.add("email");
            String template = settings.getString(KEY_EMAIL_TEMPLATE, "AIMBASE_CLI_TOKEN_EXPIRY_EMAIL");
            for (String email : to.emails()) ok &= notifier.sendEmail(template, vars, email);
        }
        if (!to.phones().isEmpty()) {
            channels.add("sms");
            String template = settings.getString(KEY_SMS_TEMPLATE, "AIMBASE_CLI_TOKEN_EXPIRY_SMS");
            for (String phone : to.phones()) ok &= notifier.sendSms(template, vars, phone);
        }
        if (!ok) {
            log.warn("[CR-196] 만료 알림 일부 실패, 기록하지 않아 다음 확인에서 다시 보냄: tenant={}, agent={}, dir={}",
                    tenantId, row.agentName(), row.configDir());
            return;
        }
        store.recordNotice(tenantId, row.agentName(), row.configDir(), expiresAt, threshold,
                String.join(",", channels), OffsetDateTime.now(clock));
        log.info("[CR-196] 만료 알림 보냄: tenant={}, agent={}, dir={}, threshold={}, channels={}",
                tenantId, row.agentName(), row.configDir(), threshold, channels);
    }

    /** 런타임 설정의 수신자. 휴대폰은 붙임표를 뗀 숫자로 보낸다. 모양이 틀린 값이 DB 에 들어 있으면 Aimbase 관리 계정 알림만 건너뛴다. */
    private Recipients platformRecipients() {
        try {
            return new Recipients(
                    List.copyOf(CliTokenRecipientService.normalizeEmails(
                            CliTokenRecipientService.splitSetting(settings.getString(KEY_EMAILS, "")))),
                    List.copyOf(CliTokenRecipientService.normalizePhones(
                            CliTokenRecipientService.splitSetting(settings.getString(KEY_PHONES, "")))));
        } catch (CliTokenApiException e) {
            log.warn("[CR-196] 런타임 설정의 만료 알림 수신자 모양이 틀려 Aimbase 관리 계정 알림을 건너뜀: {}", e.getMessage());
            return new Recipients(null, null);
        }
    }

    /**
     * 남은 날이 그 문턱 이하인 문턱 가운데 가장 작은 것. 0 은 기한 당일과 그 뒤를 맡는다.
     * 어느 문턱에도 들지 않으면 null 이다.
     */
    static Integer pickThreshold(long daysLeft, List<Integer> thresholds) {
        Integer best = null;
        for (int t : thresholds) {
            if (Math.max(daysLeft, 0) <= t && (best == null || t < best)) best = t;
        }
        return best;
    }

    static List<Integer> parseThresholds(String value) {
        List<Integer> out = new ArrayList<>();
        for (String s : CliTokenRecipientService.splitSetting(value)) {
            try {
                int n = Integer.parseInt(s);
                if (n >= 0) out.add(n);
            } catch (NumberFormatException e) {
                log.warn("[CR-196] {} 의 숫자가 아닌 값은 건너뜀: {}", KEY_NOTIFY_DAYS, s);
            }
        }
        return out;
    }

    static String accountName(String configDir) {
        if (configDir == null || configDir.isBlank()) return "";
        Path name = Path.of(configDir).getFileName();
        return name != null ? name.toString() : configDir;
    }
}
