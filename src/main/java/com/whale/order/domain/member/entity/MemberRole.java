package com.whale.order.domain.member.entity;

/**
 * 회원 권한
 * - CUSTOMER : 일반 고객 (주문 생성, 내 주문 조회)
 * - OWNER    : 점주 (매장 정보 수정, 직원 등록)
 * - ADMIN    : 시스템 전체 관리자
 */
public enum MemberRole {
    CUSTOMER,
    OWNER,
    ADMIN
}
