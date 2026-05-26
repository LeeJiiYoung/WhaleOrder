package com.whale.order.domain.payment.entity;

/**
 * 결제 수단
 * - CREDIT_CARD: 신용/체크카드
 * - KAKAO_PAY  : 카카오페이
 * - NAVER_PAY  : 네이버페이
 */
public enum PaymentMethod {
    CREDIT_CARD,
    KAKAO_PAY,
    NAVER_PAY
}
