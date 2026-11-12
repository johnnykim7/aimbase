package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CR-175 BIZ-149: 위젯 답 평가. 한 사람은 답 하나에 하나만 남긴다.
 * 세션을 지워도 남기려고 대화 테이블에 외래키를 두지 않는다.
 */
@Entity
@Table(name = "message_feedback")
public class MessageFeedbackEntity {

    public enum Rating { UP, DOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(name = "turn_id", columnDefinition = "uuid", nullable = false)
    private UUID turnId;

    @Column(name = "user_ref", length = 200, nullable = false)
    private String userRef;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Rating rating;

    @Type(JsonBinaryType.class)
    @Column(name = "reason_codes", columnDefinition = "jsonb", nullable = false)
    private List<String> reasonCodes = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(length = 100)
    private String model;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public UUID getTurnId() { return turnId; }
    public void setTurnId(UUID turnId) { this.turnId = turnId; }
    public String getUserRef() { return userRef; }
    public void setUserRef(String userRef) { this.userRef = userRef; }
    public Rating getRating() { return rating; }
    public void setRating(Rating rating) { this.rating = rating; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
