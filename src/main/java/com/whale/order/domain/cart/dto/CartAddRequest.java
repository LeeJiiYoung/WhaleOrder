package com.whale.order.domain.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CartAddRequest(
        @NotNull Long menuId,
        @NotNull @Min(1) Integer quantity,
        List<SelectedOptionRequest> selectedOptions
) {
    public record SelectedOptionRequest(
            Long menuOptionId,
            String optionGroup,
            String optionName,
            Integer additionalPrice
    ) {}
}
