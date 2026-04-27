package com.whale.order.domain.member.entity;

/**
 * 회원 권한
 * - CUSTOMER   : 일반 고객 (주문 생성, 내 주문 조회)
 * - BARISTA    : 바리스타 (주문 상태 변경)
 * - STORE_ADMIN: 매장 관리자 (메뉴/재고/직원 관리)
 * - OWNER      : 점주 (매장 정보 수정, 직원 등록)
 * - ADMIN      : 시스템 전체 관리자
 */
public enum MemberRole {
    CUSTOMER,
    BARISTA,
    STORE_ADMIN,
    OWNER,
    ADMIN
}
