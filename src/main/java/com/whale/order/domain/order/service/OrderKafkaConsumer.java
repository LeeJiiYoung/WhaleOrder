package com.whale.order.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderKafkaConsumer {

    private final OrderProcessingService orderProcessingService;

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
}
