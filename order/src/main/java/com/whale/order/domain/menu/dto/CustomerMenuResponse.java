package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.Menu;

import java.time.LocalDate;
import java.util.List;

public record CustomerMenuResponse(
        Long menuId,
        String name,
        String description,
        Integer basePrice,
        String category,
        String imageUrl,
        LocalDate saleStartDate,
        LocalDate saleEndDate,
        List<MenuOptionResponse> options
) {
    public static CustomerMenuResponse from(Menu menu, List<MenuOptionResponse> options) {
        return new CustomerMenuResponse(
                menu.getMenuId(),
                menu.getName(),
                menu.getDescription(),
                menu.getBasePrice(),
                menu.getCategory().name(),
                menu.getImageUrl(),
                menu.getSaleStartDate(),
                menu.getSaleEndDate(),
                options
        );
    }
}
