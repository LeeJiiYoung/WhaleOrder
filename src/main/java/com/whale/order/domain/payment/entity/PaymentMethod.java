package com.whale.order.domain.payment.entity;

/**
 * 결제 수단
 * - CREDIT_CARD: 신용/체크카드
 * - KAKAO_PAY  : 카카오페이
 * - NAVER_PAY  : 네이버페이
 */
public enum PaymentMethod {
    QUICK_TRANSFER,   // 퀵계좌이체
    CARD,             // 신용·체크카드
    TOSS_PAY,         // toss pay
    PAYCO,            // PAYCO
    KAKAO_PAY,        // kakao pay
    NAVER_PAY,        // N pay
    ETC               // 기타
}
