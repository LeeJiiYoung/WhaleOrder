package com.whale.order.global.outbox;

// Outbox row 의 발행 상태
public enum OutboxStatus {
    PENDING,    // 발행 대기
    PUBLISHED,  // 발행 완료
    FAILED      // 재시도 상한 초과, 수동 개입 필요
}