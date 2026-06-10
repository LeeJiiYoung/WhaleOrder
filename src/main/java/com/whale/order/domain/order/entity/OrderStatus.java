package com.whale.order.domain.order.entity;

/**
 * 주문 상태 흐름
 * PENDING → PREPARING → COMPLETED
 *         ↘ CANCELLED
 */
public enum OrderStatus {
    PENDING,    // 결제 완료, 매장 접수 대기
    PREPARING,  // 제조 중
    COMPLETED,  // 제조 완료, 수령 가능
    CANCELLED   // 주문 취소
}
