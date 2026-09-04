package com.whale.order.domain.payment.client;

/**
 * 토스페이먼츠 결제 취소 API 응답 (필요한 필드만).
 *
 * <p>주의: status 값은 우리 {@code PaymentStatus.CANCELLED}(L 두 개)와 철자가 다르다 —
 * 토스는 "CANCELED"를 쓴다. 로그/디버깅 시 헷갈리지 않도록 주석으로 남긴다.</p>
 */
public record TossCancelResponse(
        String paymentKey,
        String orderId,
        String status,
        Long balanceAmount
) {
}
