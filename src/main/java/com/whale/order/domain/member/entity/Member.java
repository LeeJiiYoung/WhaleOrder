package com.whale.order.domain.member.entity;

import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 Entity.
 * 자체 회원가입(LOCAL)과 카카오 소셜 로그인(KAKAO)을 하나의 테이블로 통합 관리한다.
 * - LOCAL : id, password 필수 / providerId null
 * - KAKAO : providerId 필수 / id, password null
 */
@Entity
@Table(
    name = "member",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_member_provider",
        columnNames = {"provider", "provider_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long memberId;

    // 자체 회원가입 로그인 ID (카카오 로그인 시 null)
    @Column(unique = true)
    private String id;

    // 자체 회원가입 비밀번호 - BCrypt 암호화하여 저장 (카카오 로그인 시 null)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    // 서비스 내 표시 이름
    private String nickname;

    private String phone;

    // 로그인 제공자 구분 (LOCAL / KAKAO)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    // 카카오 회원번호 - 자체 로그인 시 null
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Builder
    public Member(String id, String password, String name, String nickname,
                  String phone, AuthProvider provider, String providerId, MemberRole role) {
        this.id = id;
        this.password = password;
        this.name = name;
        this.nickname = nickname;
        this.phone = phone;
        this.provider = provider;
        this.providerId = providerId;
        this.role = role;
    }

    // 카카오에서 닉네임이 변경된 경우 동기화
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updatePhone(String phone) {
        this.phone = phone;
    }

    // 비밀번호 변경 - 암호화는 서비스 계층에서 처리
    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
