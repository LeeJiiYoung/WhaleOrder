package com.whale.order.domain.order.entity;

/**
 * 주문 상태 흐름
 * PENDING → ACCEPTED → PREPARING → COMPLETED
 *                    ↘ CANCELLED
 */
public enum OrderStatus {
    PENDING,    // 결제 완료, 매장 접수 대기
    ACCEPTED,   // 매장 접수 완료
    PREPARING,  // 제조 중
    COMPLETED,  // 제조 완료, 수령 가능
    CANCELLED   // 주문 취소
}
