package com.platform.clitoken;

import com.platform.domain.CliTokenRecipientEntity;
import com.platform.domain.CliTokenRecipientEntity.Channel;
import com.platform.repository.CliTokenRecipientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CR-196 PRD-646: 이 테넌트 소비앱 계정의 만료 알림 수신자(Tenant DB). Aimbase 관리 계정의 수신자는 런타임 설정에 있고
 * 같은 모양 검사를 쓴다(4.7).
 */
@Service
public class CliTokenRecipientService {

    public static final String ERR_INVALID = "recipient_invalid:";

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,]+@[^\\s@,]+\\.[^\\s@,]+$");
    /** bp-notification 문자 수신 번호는 국내 휴대폰 숫자만 받는다. 붙임표와 빈칸은 떼어 낸다. */
    private static final Pattern PHONE = Pattern.compile("^01[016789][0-9]{7,8}$");

    private final CliTokenRecipientRepository repository;

    public CliTokenRecipientService(CliTokenRecipientRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Recipients get() {
        List<String> emails = new ArrayList<>();
        List<String> phones = new ArrayList<>();
        for (CliTokenRecipientEntity r : repository.findAll()) {
            (r.getChannel() == Channel.EMAIL ? emails : phones).add(r.getAddress());
        }
        return new Recipients(emails, phones);
    }

    /** 목록 전체를 바꾼다. 하나라도 모양이 틀리면 아무것도 바꾸지 않는다. */
    @Transactional
    public Recipients replace(Recipients requested) {
        Set<String> emails = normalizeEmails(requested.emails());
        Set<String> phones = normalizePhones(requested.phones());
        repository.deleteAllInBatch();
        List<CliTokenRecipientEntity> rows = new ArrayList<>();
        emails.forEach(e -> rows.add(new CliTokenRecipientEntity(Channel.EMAIL, e)));
        phones.forEach(p -> rows.add(new CliTokenRecipientEntity(Channel.SMS, p)));
        repository.saveAll(rows);
        return new Recipients(List.copyOf(emails), List.copyOf(phones));
    }

    /** 쉼표로 적은 런타임 설정 값을 목록으로 읽는다. 모양 검사는 저장할 때 했다. */
    public static List<String> splitSetting(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public static Set<String> normalizeEmails(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String s = v == null ? "" : v.strip();
            if (s.isEmpty()) continue;
            if (!EMAIL.matcher(s).matches()) throw invalid(s);
            out.add(s);
        }
        return out;
    }

    public static Set<String> normalizePhones(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String raw = v == null ? "" : v.strip();
            if (raw.isEmpty()) continue;
            String s = raw.replaceAll("[\\s-]", "");
            if (!PHONE.matcher(s).matches()) throw invalid(raw);
            out.add(s);
        }
        return out;
    }

    private static CliTokenApiException invalid(String value) {
        return new CliTokenApiException(HttpStatus.BAD_REQUEST, ERR_INVALID + value);
    }
}
