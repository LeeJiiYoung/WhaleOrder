package com.whale.order.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Outbox 발행 예약 서비스.
// enqueue() 는 반드시 호출자 트랜잭션(pay 등) 안에서 호출되어야 원자성이 확보된다.
// propagation=REQUIRED 유지 (기본값). REQUIRES_NEW 로 바꾸면 원자성 깨짐.
@Service
@RequiredArgsConstructor
public class KafkaOutboxService {

    private final KafkaOutboxRepository outboxRepository;

    @Transactional
    public KafkaOutbox enqueue(String topic, Long aggregateId, String payload) {
        // 예외는 그대로 상위에 던져 호출자 트랜잭션 롤백을 유발한다. catch 금지.
        return outboxRepository.save(KafkaOutbox.enqueue(topic, aggregateId, payload));
    }
}