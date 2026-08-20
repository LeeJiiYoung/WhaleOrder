package com.whale.order.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 매일 새벽 3시 실행. 7일 지난 PUBLISHED row 삭제.
// 실패해도 다음 날 재시도되므로 예외 처리 최소화.
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaOutboxCleanupJob {

    private static final int RETENTION_DAYS = 7;

    private final KafkaOutboxRepository outboxRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = outboxRepository.deleteByStatusAndPublishedAtBefore(
                OutboxStatus.PUBLISHED, cutoff);
        log.info("[Outbox] 청소 완료 deleted={} cutoff={}", deleted, cutoff);
    }
}