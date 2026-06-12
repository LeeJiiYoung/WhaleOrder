package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.stock.entity.Stock;

import java.util.List;

public record StoreMenuResponse(
        Long menuId,
        String name,
        String description,
        Integer basePrice,
        String category,
        String imageUrl,
        boolean soldOut,
        Integer quantity,   // null = 무제한
        List<MenuOptionResponse> options
) {
    public static StoreMenuResponse of(Menu menu, Stock stock, List<MenuOptionResponse> options) {
        boolean soldOut = stock != null && stock.getQuantity() >= 0 && stock.getQuantity() == 0;
        Integer quantity = (stock == null || stock.getQuantity() < 0) ? null : stock.getQuantity();

        return new StoreMenuResponse(
                menu.getMenuId(),
                menu.getName(),
                menu.getDescription(),
                menu.getBasePrice(),
                menu.getCategory().name(),
                menu.getImageUrl(),
                soldOut,
                quantity,
                options
        );
    }
}
