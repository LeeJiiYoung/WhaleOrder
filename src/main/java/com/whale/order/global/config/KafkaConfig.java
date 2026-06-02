package com.whale.order.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    // 주문 생성 이벤트 토픽
    // partition=3: Consumer 3개가 병렬 처리 가능
    // replicas=1: 개발환경이라 복제본 1개 (운영은 3으로 늘림)
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name("order-created")
                .partitions(3)
                .replicas(1)
                .build();
    }

    // 실패 메시지 보관 토픽 (order-created.DLT)
    // DeadLetterPublishingRecoverer가 기본적으로 원본토픽명 + .DLT 로 라우팅
    @Bean
    public NewTopic orderCreatedDltTopic() {
        return TopicBuilder.name("order-created.DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    // 실패 메시지 처리 전략
    // 1초 간격으로 3회 재시도 → 그래도 실패 시 DLT로 이동
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(1000L, 3));

        // 재시도해도 의미없는 예외는 바로 DLT로 이동
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class
        );

        return handler;
    }
}
