package com.whale.order.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface KafkaOutboxRepository extends JpaRepository<KafkaOutbox, Long> {

    // 발행 대기 row 를 오래된 것부터 최대 100건 조회 (partial index 활용)
    List<KafkaOutbox> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    // 청소 배치용. 삭제된 건수 반환. clearAutomatically 로 L1 캐시 정리.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM KafkaOutbox o WHERE o.status = :status AND o.publishedAt < :cutoff")
    long deleteByStatusAndPublishedAtBefore(@Param("status") OutboxStatus status,
                                             @Param("cutoff") LocalDateTime cutoff);

    // Prometheus gauge 용
    long countByStatus(OutboxStatus status);

    // 테스트 편의: published_at 강제 세팅 (프로덕션 코드에서 사용 금지)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE KafkaOutbox o SET o.publishedAt = :ts WHERE o.id = :id")
    void updatePublishedAtForTest(@Param("id") Long id, @Param("ts") LocalDateTime ts);
}