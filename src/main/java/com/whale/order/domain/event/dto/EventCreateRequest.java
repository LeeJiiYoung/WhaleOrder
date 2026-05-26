package com.whale.order.domain.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record EventCreateRequest(
        @NotBlank(message = "이벤트 이름을 입력해주세요") String name,
        @NotNull(message = "굿즈를 선택해주세요") Long goodsId,
        @NotNull(message = "매장을 선택해주세요") Long storeId,
        @NotNull(message = "오픈 시각을 선택해주세요") LocalDateTime openAt,
        @NotNull @Min(1) Integer capacity,
        @NotNull @Min(1) Integer perPersonLimit
) {}
