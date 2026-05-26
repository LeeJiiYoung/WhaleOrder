package com.whale.order.domain.member.dto;

import com.whale.order.domain.member.entity.Member;

public record MemberSearchResponse(
        Long memberId,
        String userId,
        String name,
        String nickname,
        String phone
) {
    public static MemberSearchResponse from(Member member) {
        return new MemberSearchResponse(
                member.getMemberId(),
                member.getUserId(),
                member.getName(),
                member.getNickname(),
                member.getPhone()
        );
    }
}
