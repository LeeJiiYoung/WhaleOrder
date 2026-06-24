package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PaymentRequest(
        @NotNull(message = "결제 수단을 선택해주세요") PaymentMethod method,
        @NotNull(message = "매장을 선택해주세요") Long storeId,
        @NotNull(message = "주문 방식을 선택해주세요") OrderType orderType,
        // 클라이언트가 결제 화면에서 본 표시 금액. 서버는 cart 기준으로 재계산해 일치 여부 검증.
        // 실제 청구액은 항상 서버 cart 기준 — 이 값은 비교용일 뿐 PG 에 전달되지 않음.
        @NotNull(message = "결제 금액 정보가 누락되었습니다")
        @PositiveOrZero(message = "결제 금액은 0 이상이어야 합니다") Long expectedAmount,
        String customerRequest
) {}
