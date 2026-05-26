package com.whale.order.domain.member.dto;

public record MyProfileUpdateRequest(
        String nickname,
        String phone
) {}
