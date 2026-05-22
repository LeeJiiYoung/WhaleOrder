package com.whale.order.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GoodsCreateRequest(
        @NotBlank(message = "굿즈 이름을 입력해주세요") String name,
        String description,
        @NotNull(message = "가격을 입력해주세요") Integer price,
        @NotNull(message = "매장을 선택해주세요") Long storeId,
        String imageUrl
) {}
