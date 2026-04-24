package com.whale.order.domain.store.entity;

/**
 * 매장 운영 상태
 * - OPEN  : 운영 중 (주문 접수 가능)
 * - CLOSED: 마감 (주문 불가)
 */
public enum StoreStatus {
    OPEN,
    CLOSED
}
