package com.platform.clitoken;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** CR-196 PRD-646: 1년 토큰 만료 알림 수신자. 메일 주소와 휴대폰 번호 목록. */
public record Recipients(@JsonProperty("emails") List<String> emails,
                         @JsonProperty("phones") List<String> phones) {

    public Recipients {
        emails = emails != null ? List.copyOf(emails) : List.of();
        phones = phones != null ? List.copyOf(phones) : List.of();
    }

    @JsonIgnore
    public boolean isEmpty() {
        return emails.isEmpty() && phones.isEmpty();
    }
}
