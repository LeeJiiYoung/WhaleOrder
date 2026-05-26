package com.whale.order.domain.member.dto;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;

import java.time.LocalDateTime;

public record MemberResponse(
        Long memberId,
        String userId,
        String name,
        String nickname,
        String phone,
        MemberRole role,
        AuthProvider provider,
        LocalDateTime createdAt
) {
    public static MemberResponse from(Member m) {
        return new MemberResponse(
                m.getMemberId(),
                m.getUserId(),
                m.getName(),
                m.getNickname(),
                m.getPhone(),
                m.getRole(),
                m.getProvider(),
                m.getCreatedAt()
        );
    }
}
