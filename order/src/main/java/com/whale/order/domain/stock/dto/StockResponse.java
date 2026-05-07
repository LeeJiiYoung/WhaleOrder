package com.whale.order.domain.stock.dto;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.stock.entity.Stock;

public record StockResponse(
        Long menuId,
        String menuName,
        String category,
        Integer quantity,   // null = 무제한
        boolean unlimited
) {
    public static StockResponse of(Menu menu, Stock stock) {
        if (stock == null || stock.getQuantity() < 0) {
            return new StockResponse(menu.getMenuId(), menu.getName(),
                    menu.getCategory().name(), null, true);
        }
        return new StockResponse(menu.getMenuId(), menu.getName(),
                menu.getCategory().name(), stock.getQuantity(), false);
    }
}
