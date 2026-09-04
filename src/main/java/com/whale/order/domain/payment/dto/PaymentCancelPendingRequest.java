package com.whale.order.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 결제 대기(AWAITING_PAYMENT) 주문 정리 요청.
 *
 * <p>토스 결제창이 취소/거절되어 successUrl까지 가지 못했을 때 프런트가 보낸다.
 * orderId는 confirm과 동일하게 토스 전용 형식("whale-17")을 그대로 넘긴다.</p>
 */
public record PaymentCancelPendingRequest(
        @NotBlank(message = "주문 ID가 누락되었습니다") String orderId
) {
}
