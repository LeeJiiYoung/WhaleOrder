package com.whale.order.domain.payment.client;

/**
 * 토스 결제 승인 API 응답 (필요한 필드만 매핑 — 전체 스펙은 훨씬 많음).
 *
 * method는 "카드"/"계좌이체"/"간편결제" 등 한글 대분류로 오고, 간편결제(카카오페이·네이버페이·
 * 토스페이 등)일 때만 easyPay.provider로 실제 제공사가 따로 온다.
 */
public record TossConfirmResponse(
        String paymentKey,
        String orderId,
        String status,
        Long totalAmount,
        String method,
        EasyPay easyPay
) {
    public record EasyPay(String provider) {}
}
