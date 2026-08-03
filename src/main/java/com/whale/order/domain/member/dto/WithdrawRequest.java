package com.whale.order.domain.member.dto;

import com.whale.order.domain.member.entity.WithdrawReason;

/**
 * 회원 탈퇴 요청.
 *
 * <p>LOCAL 회원은 비밀번호 재확인이 필요하지만 KAKAO 회원은 보낼 값이 없어 body 자체를 생략할 수 있다.
 * 따라서 필드에 {@code @NotBlank} 를 걸지 않고 프로바이더에 따라 서비스에서 검증한다.
 *
 * @param password LOCAL 회원만 필수. KAKAO 는 password 가 null 이라 검증 대상이 아니다.
 * @param reason   탈퇴 사유. 선택 입력이라 null 을 허용한다 — 사유를 안 골랐다고 탈퇴를 막으면
 *                 나가기 어렵게 만드는 다크패턴이 된다. 통계는 응답한 건만 집계한다.
 */
public record WithdrawRequest(String password, WithdrawReason reason) {
}
