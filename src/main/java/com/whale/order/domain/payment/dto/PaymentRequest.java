package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotNull(message = "결제 수단을 선택해주세요") PaymentMethod method,
        @NotNull(message = "매장을 선택해주세요") Long storeId,
        @NotNull(message = "주문 방식을 선택해주세요") OrderType orderType,
        String customerRequest
) {}
