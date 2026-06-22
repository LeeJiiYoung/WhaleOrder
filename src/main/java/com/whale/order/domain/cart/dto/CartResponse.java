package com.whale.order.domain.cart.dto;

import java.util.List;

public record CartResponse(
        List<CartItem> items,
        long totalPrice,
        int totalCount
) {}
