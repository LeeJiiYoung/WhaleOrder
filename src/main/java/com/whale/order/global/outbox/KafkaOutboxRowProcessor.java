package com.whale.order.global.outbox;

import com.whale.order.domain.order.service.OrderKafkaProducer;
import com.whale.order.domain.order.service.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 단일 outbox row 를 하나의 트랜잭션으로 발행 처리.
// KafkaOutboxWorker 와 분리한 이유: 같은 클래스 self-invocation 은 Spring 프록시를 우회해
// @Transactional 이 안 걸리므로 별도 빈으로 분리.
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOutboxRowProcessor {

    private static final int MAX_ATTEMPTS = 5;

    private final KafkaOutboxRepository outboxRepository;
    private final Optional<OrderKafkaProducer> kafkaProducer;   // prod 프로필은 empty
    private final OrderProcessingService processingService;
    private final KafkaOutboxMetrics metrics;

    @Transactional
    public void processOne(Long outboxId) {
        KafkaOutbox row = outboxRepository.findById(outboxId).orElse(null);
        if (row == null || row.getStatus() != OutboxStatus.PENDING) return;

        try {
            publish(row);
            row.markPublished();
            metrics.incrementPublished();
        } catch (Exception e) {
            // 예외 삼킴 = 의도적. 상태 업데이트(attempts++) 가 커밋되어야 하기 때문.
            log.warn("[Outbox] 발행 실패 outboxId={} attempts={} error={}",
                    outboxId, row.getAttempts() + 1, e.getMessage());
            row.markFailedOrRetry(e, MAX_ATTEMPTS);
            if (row.getStatus() == OutboxStatus.FAILED) {
                metrics.incrementFailed();
            }
        }
    }

    // dev: Kafka 발행 / prod: OrderProcessingService.process() 직접 호출
    private void publish(KafkaOutbox row) {
        if (kafkaProducer.isPresent()) {
            kafkaProducer.get().publish(row.getAggregateId());
        } else {
            processingService.process(row.getAggregateId());
        }
    }
}