package com.whale.order.domain.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MenuOptionRequest(
        @NotBlank(message = "옵션 그룹명은 필수입니다.")
        String optionGroup,

        @NotBlank(message = "옵션 값은 필수입니다.")
        String optionName,

        @NotNull(message = "추가 금액은 필수입니다.")
        @Min(value = 0, message = "추가 금액은 0원 이상이어야 합니다.")
        Integer additionalPrice
) {}
