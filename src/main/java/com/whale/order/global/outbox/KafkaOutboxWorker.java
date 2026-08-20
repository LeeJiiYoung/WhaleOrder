package com.whale.order.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 1초 주기로 PENDING outbox row 를 폴링해 발행 처리.
// row 단위 트랜잭션은 KafkaOutboxRowProcessor 에 위임 (Spring 프록시 우회 방지).
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOutboxWorker {

    private final KafkaOutboxRepository outboxRepository;
    private final KafkaOutboxRowProcessor rowProcessor;

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<KafkaOutbox> pending = outboxRepository
                .findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) return;

        log.debug("[Outbox] 폴링 처리 시작 count={}", pending.size());
        for (KafkaOutbox row : pending) {
            try {
                rowProcessor.processOne(row.getId());
            } catch (Exception e) {
                // 여기 도달 = RowProcessor 내부 catch 도 못 잡은 예외 (DB 순단 등).
                // 다음 폴링에 다시 잡히도록 두고 계속 진행.
                log.error("[Outbox] 워커 처리 실패 outboxId={}", row.getId(), e);
            }
        }
    }
}