package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.MenuOption;

public record MenuOptionResponse(
        Long menuOptionId,
        String optionGroup,
        String optionName,
        Integer additionalPrice
) {
    public static MenuOptionResponse from(MenuOption option) {
        return new MenuOptionResponse(
                option.getMenuOptionId(),
                option.getOptionGroup(),
                option.getOptionName(),
                option.getAdditionalPrice()
        );
    }
}
