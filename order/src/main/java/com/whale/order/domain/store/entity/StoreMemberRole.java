package com.whale.order.domain.store.entity;

/**
 * 매장 내 직원 역할
 * - BARISTA    : 음료 제조 및 주문 상태 변경
 * - STORE_ADMIN: 메뉴, 재고, 직원 관리
 */
public enum StoreMemberRole {
    BARISTA,
    STORE_ADMIN
}
