package com.whale.order.domain.member.entity;

/**
 * 로그인 제공자
 * - LOCAL: 자체 회원가입 (ID/PW 방식)
 * - KAKAO: 카카오 소셜 로그인 (OAuth2)
 */
public enum AuthProvider {
    LOCAL,
    KAKAO
}
