package com.whale.order.domain.order.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderKafkaConsumer {

    private final OrderProcessingService orderProcessingService;
    private final MeterRegistry meterRegistry;

    // groupId: 같은 그룹의 Consumer들이 파티션을 나눠서 처리
    // 파티션 3개 → Consumer 최대 3개까지 병렬 처리 가능
    @KafkaListener(topics = "order-created", groupId = "whale-order")
    public void consume(Long orderId) {
        log.info("Kafka 수신 orderId={}", orderId);
        try {
            orderProcessingService.process(orderId);
        } catch (Exception e) {
            log.error("Kafka 처리 실패 orderId={} error={}", orderId, e.getMessage());
            // 처리 실패 시 Kafka가 자동으로 재시도 (offset 커밋 안 됨)
            throw e;
        }
    }

    // 3회 재시도 후에도 실패한 메시지가 DLT로 이동되면 여기서 처리
    // Saga 보상 트랜잭션 실행 (재고 복구 → 결제 취소 → 주문 취소)
    @KafkaListener(topics = "order-created.DLT", groupId = "whale-order-dlt")
    public void consumeDlt(Long orderId,
                           @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage) {
        log.error("DLT 수신 orderId={} 원인={}", orderId, exceptionMessage);
        Counter.builder("kafka.dlt.received")
                .description("DLT 수신 횟수 — 3회 재시도 후에도 처리 실패한 메시지")
                .register(meterRegistry).increment();
        try {
            orderProcessingService.compensate(orderId);
        } catch (Exception e) {
            // 보상 트랜잭션까지 실패하면 관리자 개입 필요 — 더 이상 재시도 안 함
            log.error("보상 트랜잭션 실패 orderId={} 관리자 확인 필요 error={}", orderId, e.getMessage());
        }
    }
}
