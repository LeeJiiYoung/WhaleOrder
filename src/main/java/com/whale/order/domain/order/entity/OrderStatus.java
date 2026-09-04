package com.whale.order.domain.order.entity;

/**
 * 주문 상태 흐름
 * AWAITING_PAYMENT → PENDING → PREPARING → COMPLETED
 *                        ↘ CANCELLED   ↖(AWAITING_PAYMENT도 결제 실패 시 CANCELLED로)
 */
public enum OrderStatus {
    // 토스 결제창 호출 전 prepare()가 만든 임시 상태 — 아직 결제가 승인되지 않았다.
    // 매장 관리자 화면(어드민 주문 목록)에는 노출되지 않으며, confirm() 성공 시 PENDING으로 전이한다.
    // 결제창 취소·거절·이탈 등으로 결제가 끝내 확정되지 않으면 CANCELLED로 정리된다.
    AWAITING_PAYMENT,
    PENDING,    // 결제 승인 완료, 매장 접수 대기
    PREPARING,  // 제조 중
    COMPLETED,  // 제조 완료, 수령 가능
    CANCELLED   // 주문 취소 (결제 미완료로 인한 정리 포함)
}
