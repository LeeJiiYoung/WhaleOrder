package com.whale.order.global.auth.jwt;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.entity.WithdrawReason;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 회원의 잔여 access token 처리 검증.
 *
 * <p>JWT 필터는 매 요청 DB를 조회하므로 탈퇴 즉시 토큰이 무효화된다. 다만 필터에서 던진 예외는
 * {@code @RestControllerAdvice} 가 잡지 못해 500 이 된다. 인증만 건너뛰고
 * SecurityConfig 의 authenticationEntryPoint 가 401 을 내야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class WithdrawnMemberAuthTest extends TestContainerBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JwtProvider jwtProvider;

    @Test
    @DisplayName("탈퇴 회원의 유효한 access token 으로 요청하면 401 을 받는다")
    void 탈퇴회원_토큰_401() throws Exception {
        // given: 정상 회원에게 토큰을 발급한 뒤 탈퇴시킨다
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // when & then: 토큰 서명은 유효하지만 살아있는 회원이 아니므로 401
        mockMvc.perform(get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정상 회원의 access token 으로는 내 정보를 조회할 수 있다")
    void 정상회원_토큰_200() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("younghee").password("encoded").name("김영희")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
