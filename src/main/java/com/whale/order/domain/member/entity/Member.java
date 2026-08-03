package com.whale.order.domain.member.entity;

import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원 Entity.
 * 자체 회원가입(LOCAL)과 카카오 소셜 로그인(KAKAO)을 하나의 테이블로 통합 관리한다.
 * - LOCAL : id, password 필수 / providerId null
 * - KAKAO : providerId 필수 / id, password null
 *
 * <p>탈퇴 회원({@code isDeleted = true})을 숨기는 {@code @SQLRestriction} 은 일부러 걸지 않는다.
 * {@link #withdraw()} 가 개인정보를 이미 익명화하므로 전역으로 숨길 이유가 없고,
 * 오히려 {@code orders → member} 연관까지 끊어 탈퇴 회원의 주문이 매장·어드민 목록에서
 * 통째로 사라지는 문제가 있었다. 대신 로그인·인증·어드민 목록 등 실제로 막아야 하는 지점에서만
 * {@code isDeleted = false} 를 명시적으로 건다.
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
    private String userId;

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

    @Column(nullable = false)
    private boolean isDeleted = false;

    // 탈퇴 사유 - 선택 입력이라 미응답이면 null
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private WithdrawReason withdrawReason;

    // 탈퇴 시각 - BaseEntity.updatedAt 은 모든 수정에 갱신되므로 탈퇴 시각으로 쓸 수 없다
    private LocalDateTime withdrawnAt;

    @Builder
    public Member(String userId, String password, String name, String nickname,
                  String phone, AuthProvider provider, String providerId, MemberRole role) {
        this.userId = userId;
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

    public void updateName(String name) {
        this.name = name;
    }

    public void updateRole(MemberRole role) {
        this.role = role;
    }

    /**
     * 회원 탈퇴 — 개인정보를 익명화하고 삭제 상태로 전환한다.
     *
     * <p>userId 와 providerId 는 unique 제약 슬롯을 반납해 같은 계정으로 재가입할 수 있게 한다.
     * name 은 nullable=false 이면서 주문 목록 표시에 쓰이므로 고정 문구로 대체한다.
     * nickname 을 null 로 두면 OrderResponse 의 기존 fallback 이 name 을 집어 "탈퇴한 회원"을 출력한다.
     *
     * <p>주문·결제 row 와 FK 는 건드리지 않는다. 매장의 매출 집계를 지키기 위함이다.
     *
     * @param reason 탈퇴 사유. 선택 입력이라 미응답이면 null 이 들어온다.
     *               탈퇴를 막는 조건을 늘리지 않기 위해 필수로 두지 않았다.
     */
    public void withdraw(WithdrawReason reason) {
        this.withdrawReason = reason;
        this.withdrawnAt = LocalDateTime.now();
        this.userId = "deleted_" + this.memberId;
        this.password = null;
        this.name = "탈퇴한 회원";
        this.nickname = null;
        this.phone = null;
        if (this.provider == AuthProvider.KAKAO) {
            this.providerId = "deleted_" + this.memberId;
        }
        this.isDeleted = true;
    }
}
