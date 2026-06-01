package com.whale.order.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OrderKafkaProducer {

    private static final String TOPIC = "order-created";

    // KafkaTemplate<key타입, value타입>
    // key = orderId String (파티션 라우팅용)
    // value = orderId Long (Consumer가 실제로 받는 데이터)
    private final KafkaTemplate<String, Long> kafkaTemplate;

    public void publish(Long orderId) {
        kafkaTemplate.send(TOPIC, orderId.toString(), orderId);
        log.info("Kafka 발행 topic={} orderId={}", TOPIC, orderId);
    }
}
