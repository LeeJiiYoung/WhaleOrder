package com.whale.order.global.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// Kafka 발행 예약 row.
// 상태 전이는 반드시 도메인 메서드(enqueue/markPublished/markFailedOrRetry) 를 통해야 한다.
@Entity
@Table(name = "kafka_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KafkaOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    private KafkaOutbox(String topic, Long aggregateId, String payload) {
        this.topic = topic;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = LocalDateTime.now();
    }

    // 신규 outbox row 생성. status=PENDING, attempts=0
    public static KafkaOutbox enqueue(String topic, Long aggregateId, String payload) {
        return new KafkaOutbox(topic, aggregateId, payload);
    }

    // 발행 성공 처리
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    // 발행 실패 처리. maxAttempts 도달 시 FAILED 전환.
    public void markFailedOrRetry(Exception e, int maxAttempts) {
        this.attempts++;
        this.lastError = e.getMessage();
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }
}