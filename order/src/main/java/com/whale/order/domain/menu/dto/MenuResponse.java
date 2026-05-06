package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.Menu;

import java.time.LocalDate;

public record MenuResponse(
        Long menuId,
        String name,
        String description,
        Integer basePrice,
        String category,
        String imageUrl,
        LocalDate saleStartDate,
        LocalDate saleEndDate,
        Boolean isActive,
        Boolean isOnSale
) {
    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.getMenuId(),
                menu.getName(),
                menu.getDescription(),
                menu.getBasePrice(),
                menu.getCategory().name(),
                menu.getImageUrl(),
                menu.getSaleStartDate(),
                menu.getSaleEndDate(),
                menu.getIsActive(),
                menu.isOnSale()
        );
    }
}
