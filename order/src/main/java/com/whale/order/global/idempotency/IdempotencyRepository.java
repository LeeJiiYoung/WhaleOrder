package com.whale.order.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * 키가 없을 때만 PROCESSING 레코드를 삽입한다.
     * PostgreSQL ON CONFLICT DO NOTHING으로 원자적 처리 → 삽입 건수 반환 (1=성공, 0=이미 존재)
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_key (idempotency_key, status, created_at, expires_at)
            VALUES (:key, 'PROCESSING', :now, :expiresAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertProcessingIfAbsent(@Param("key") String key,
                                  @Param("now") LocalDateTime now,
                                  @Param("expiresAt") LocalDateTime expiresAt);
}
