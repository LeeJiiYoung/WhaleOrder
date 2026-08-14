package com.whale.order.global.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Outbox 관련 Prometheus 지표 등록·노출.
// Gauge: 현재 상태 관찰. Counter: 누적 이벤트 집계.
@Component
@RequiredArgsConstructor
public class KafkaOutboxMetrics {

    private final KafkaOutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    private Counter publishedCounter;
    private Counter failedCounter;

    @PostConstruct
    void register() {
        meterRegistry.gauge("kafka_outbox_pending_count", outboxRepository,
                r -> r.countByStatus(OutboxStatus.PENDING));

        publishedCounter = Counter.builder("kafka_outbox_published_total")
                .description("Outbox 워커가 발행에 성공한 누적 건수")
                .register(meterRegistry);

        failedCounter = Counter.builder("kafka_outbox_failed_total")
                .description("Outbox row 가 FAILED 로 전환된 누적 건수")
                .register(meterRegistry);
    }

    public void incrementPublished() {
        publishedCounter.increment();
    }

    public void incrementFailed() {
        failedCounter.increment();
    }
}