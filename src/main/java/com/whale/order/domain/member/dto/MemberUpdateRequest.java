package com.whale.order.domain.member.dto;

import com.whale.order.domain.member.entity.MemberRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MemberUpdateRequest(
        @NotBlank String name,
        String nickname,
        String phone,
        @NotNull MemberRole role
) {}
