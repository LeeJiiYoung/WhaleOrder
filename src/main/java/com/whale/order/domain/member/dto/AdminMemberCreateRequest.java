package com.whale.order.domain.member.dto;

import com.whale.order.domain.member.entity.MemberRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminMemberCreateRequest(
        @NotBlank @Size(min = 4, max = 20) String userId,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String name,
        String nickname,
        String phone,
        @NotNull MemberRole role
) {}
