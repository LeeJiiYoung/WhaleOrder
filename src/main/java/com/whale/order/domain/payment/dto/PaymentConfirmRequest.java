package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.OrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PaymentConfirmRequest(@NotBlank(message = "paymentKey가 누락되었습니다") String paymentKey,
                                    @NotBlank(message = "주문 ID가 누락되었습니다") String orderId,
                                    @NotNull(message = "결제 금액 정보가 누락되었습니다")
                                    @PositiveOrZero(message = "결제 금액은 0 이상이어야 합니다") Long amount) {
}
