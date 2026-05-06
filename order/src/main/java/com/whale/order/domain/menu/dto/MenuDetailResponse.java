package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuOption;

import java.time.LocalDate;
import java.util.List;

public record MenuDetailResponse(
        Long menuId,
        String name,
        String description,
        Integer basePrice,
        String category,
        String imageUrl,
        LocalDate saleStartDate,
        LocalDate saleEndDate,
        Boolean isActive,
        Boolean isOnSale,
        List<MenuOptionResponse> options
) {
    public static MenuDetailResponse from(Menu menu, List<MenuOption> options) {
        return new MenuDetailResponse(
                menu.getMenuId(),
                menu.getName(),
                menu.getDescription(),
                menu.getBasePrice(),
                menu.getCategory().name(),
                menu.getImageUrl(),
                menu.getSaleStartDate(),
                menu.getSaleEndDate(),
                menu.getIsActive(),
                menu.isOnSale(),
                options.stream().map(MenuOptionResponse::from).toList()
        );
    }
}
