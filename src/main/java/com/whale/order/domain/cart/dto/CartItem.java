package com.whale.order.domain.cart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartItem {

    private String itemKey;   // Redis field (menuId:optionId1,optionId2,...)
    private Long menuId;
    private String menuName;
    private String imageUrl;
    private Integer basePrice;
    private Integer quantity;
    private List<SelectedOption> selectedOptions;
    private Integer unitPrice;   // basePrice + 옵션 추가금 합산
    private Integer totalPrice;  // unitPrice * quantity

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelectedOption {
        private Long menuOptionId;
        private String optionGroup;
        private String optionName;
        private Integer additionalPrice;
    }
}
