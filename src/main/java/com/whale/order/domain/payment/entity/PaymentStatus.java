package com.whale.order.domain.payment.entity;

/**
 * 결제 상태 흐름
 * PENDING → SUCCESS
 *         ↘ FAILED → (Saga 보상 트랜잭션) → CANCELLED
 */
public enum PaymentStatus {
    PENDING,    // 결제 진행 중
    SUCCESS,    // 결제 성공
    FAILED,     // 결제 실패 (PG사 오류 등)
    CANCELLED   // 결제 취소 (Saga 보상 트랜잭션)
}
