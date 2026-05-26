package com.whale.order.domain.cart.dto;

import java.util.List;

public record CartResponse(
        List<CartItem> items,
        int totalPrice,
        int totalCount
) {}
