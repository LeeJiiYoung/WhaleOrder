package com.whale.order.domain.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record StoreCreateRequest(

        @NotBlank(message = "매장명을 입력해주세요")
        String name,

        @NotBlank(message = "우편번호를 입력해주세요")
        String postalCode,

        @NotBlank(message = "주소를 입력해주세요")
        String address,

        String addressDetail,

        String phone,

        @NotNull(message = "영업 시작 시간을 입력해주세요")
        LocalTime openTime,

        @NotNull(message = "영업 종료 시간을 입력해주세요")
        LocalTime closeTime,

        @NotBlank(message = "점주 아이디를 입력해주세요")
        String ownerUserId
) {}
