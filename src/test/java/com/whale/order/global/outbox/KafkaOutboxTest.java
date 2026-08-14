package com.whale.order.global.outbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaOutboxTest {

    @Test
    void enqueue_은_PENDING_상태_attempts0_생성() {
        KafkaOutbox outbox = KafkaOutbox.enqueue("order-created", 42L, "{\"orderId\":42}");

        assertThat(outbox.getTopic()).isEqualTo("order-created");
        assertThat(outbox.getAggregateId()).isEqualTo(42L);
        assertThat(outbox.getPayload()).isEqualTo("{\"orderId\":42}");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttempts()).isZero();
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getCreatedAt()).isNotNull();
    }

    @Test
    void markPublished_는_상태_전이_publishedAt_세팅() {
        KafkaOutbox outbox = KafkaOutbox.enqueue("order-created", 1L, "{}");
        LocalDateTime before = LocalDateTime.now();

        outbox.markPublished();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getPublishedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void markFailedOrRetry_는_임계이하_PENDING_유지_attempts_증가() {
        KafkaOutbox outbox = KafkaOutbox.enqueue("order-created", 1L, "{}");

        outbox.markFailedOrRetry(new RuntimeException("kafka down"), 5);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getLastError()).contains("kafka down");
    }

    @Test
    void markFailedOrRetry_는_임계도달_FAILED_전환() {
        KafkaOutbox outbox = KafkaOutbox.enqueue("order-created", 1L, "{}");
        // 4번 실패 → attempts=4, PENDING 유지
        for (int i = 0; i < 4; i++) outbox.markFailedOrRetry(new RuntimeException("err"), 5);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttempts()).isEqualTo(4);

        // 5번째 실패 → attempts=5, FAILED
        outbox.markFailedOrRetry(new RuntimeException("final"), 5);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.getAttempts()).isEqualTo(5);
        assertThat(outbox.getLastError()).contains("final");
    }
}