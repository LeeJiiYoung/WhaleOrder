package com.whale.order.global.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    private static final int PROCESSING_TIMEOUT_SECONDS = 30;

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public static IdempotencyRecord processing(String key) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.key = key;
        record.status = IdempotencyStatus.PROCESSING;
        record.createdAt = LocalDateTime.now();
        record.expiresAt = LocalDateTime.now().plusHours(24);
        return record;
    }

    public void complete(String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseBody = responseBody;
        // 완료 시점부터 10분간 재시도 응답 보관 (네트워크 재시도 보호 윈도우)
        this.expiresAt = LocalDateTime.now().plusMinutes(10);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** 30초 이상 PROCESSING 상태이면 타임아웃으로 간주 → 재시도 허용 */
    public boolean isProcessingTimedOut() {
        return status == IdempotencyStatus.PROCESSING
                && createdAt.plusSeconds(PROCESSING_TIMEOUT_SECONDS).isBefore(LocalDateTime.now());
    }
}
