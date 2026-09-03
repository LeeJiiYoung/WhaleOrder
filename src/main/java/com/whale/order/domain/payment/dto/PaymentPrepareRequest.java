package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.OrderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PaymentPrepareRequest(@NotNull(message = "매장을 선택해주세요") Long storeId,
                                    @NotNull(message = "주문 방식을 선택해주세요") OrderType orderType,
                                    @NotNull(message = "결제 금액 정보가 누락되었습니다") @PositiveOrZero(message = "결제 금액은 0 이상이어야 합니다") Long expectedAmount,
                                    String customerRequest) {
}
