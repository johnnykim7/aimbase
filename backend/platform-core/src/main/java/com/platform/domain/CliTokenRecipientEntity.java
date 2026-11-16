package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** CR-196 PRD-646: 이 테넌트 소비앱 계정의 1년 토큰 만료 알림 수신자 한 사람의 메일이나 휴대폰. */
@Entity
@Table(name = "cli_token_recipients")
public class CliTokenRecipientEntity {

    public enum Channel { EMAIL, SMS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Channel channel;

    @Column(length = 200, nullable = false)
    private String address;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected CliTokenRecipientEntity() {}

    public CliTokenRecipientEntity(Channel channel, String address) {
        this.channel = channel;
        this.address = address;
    }

    public Long getId() { return id; }
    public Channel getChannel() { return channel; }
    public String getAddress() { return address; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
