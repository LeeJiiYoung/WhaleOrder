package com.whale.order.domain.member.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String nickname,
        String role
) {
}
