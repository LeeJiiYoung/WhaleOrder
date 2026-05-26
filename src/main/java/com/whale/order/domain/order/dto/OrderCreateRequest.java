package com.whale.order.domain.order.dto;

import com.whale.order.domain.order.entity.OrderType;
import jakarta.validation.constraints.NotNull;

public record OrderCreateRequest(
        @NotNull Long storeId,
        @NotNull OrderType orderType,
        String customerRequest
) {}
