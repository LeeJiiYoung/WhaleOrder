package com.whale.order.domain.member.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.entity.WithdrawReason;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.RefreshTokenService;
import com.whale.order.global.auth.jwt.JwtProvider;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴 회원의 리프레시 토큰 재발급 차단 검증.
 *
 * <p>{@code @SQLRestriction} 을 제거해 findById 가 탈퇴 회원도 반환하므로,
 * Redis 정리가 실패해 토큰이 남아있으면 탈퇴자가 새 토큰을 받을 수 있다.
 * 그 상황을 재현하기 위해 탈퇴 처리 후 토큰을 다시 저장한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberRefreshTest extends TestContainerBase {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtProvider jwtProvider;

    @Test
    @DisplayName("탈퇴 회원의 리프레시 토큰으로는 재발급받을 수 없다")
    void 탈퇴회원_리프레시_차단() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();
        String refreshToken = jwtProvider.generateRefreshToken(id);

        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // Redis 정리가 실패해 토큰이 남아있는 상황을 재현
        refreshTokenService.save(id, refreshToken);

        // when & then
        assertThatThrownBy(() -> memberService.refresh(refreshToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 회원입니다");
    }

    @Test
    @DisplayName("정상 회원은 리프레시로 새 토큰을 발급받는다")
    void 정상회원_리프레시_성공() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("younghee").password("encoded").name("김영희").nickname("영희")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String refreshToken = jwtProvider.generateRefreshToken(member.getMemberId());
        refreshTokenService.save(member.getMemberId(), refreshToken);

        // when
        var response = memberService.refresh(refreshToken);

        // then
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }
}
