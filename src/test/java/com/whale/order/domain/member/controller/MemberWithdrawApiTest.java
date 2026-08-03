package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.jwt.JwtProvider;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/members/me} 엔드투엔드 검증.
 *
 * <p>SecurityConfig 의 역할 제한이 실제로 403 을 내는지, 탈퇴 직후 같은 토큰이
 * 401 로 막히는지를 HTTP 계층에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawApiTest extends TestContainerBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("CUSTOMER 가 비밀번호를 맞게 보내면 200 과 함께 탈퇴된다")
    void 탈퇴_성공_200() throws Exception {
        // given
        Member member = 회원_생성("chulsoo", MemberRole.CUSTOMER);
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\",\"reason\":\"NOT_USING\"}"))
                .andExpect(status().isOk());

        assertThat(memberRepository.findById(member.getMemberId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400 과 함께 원인을 알려주는 메시지를 받는다")
    void 비밀번호_불일치_400() throws Exception {
        // given
        Member member = 회원_생성("chulsoo", MemberRole.CUSTOMER);
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then: 상태 코드뿐 아니라 사용자에게 그대로 노출되는 문구까지 검증한다
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호가 맞지 않습니다"));
    }

    @Test
    @DisplayName("Content-Type 없이 body 를 보내도 500 이 아니라 원인을 알 수 있는 400 을 받는다")
    void ContentType_누락_400() throws Exception {
        // given: axios 의 delete(url, { data }) 가 Content-Type 을 안 붙이는 경우를 재현
        Member member = 회원_생성("chulsoo", MemberRole.CUSTOMER);
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .content("{\"password\":\"pw1234!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("깨진 JSON 을 보내도 500 이 아니라 400 을 받는다")
    void 잘못된_body_400() throws Exception {
        // given
        Member member = 회원_생성("chulsoo", MemberRole.CUSTOMER);
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then: reason 에 enum 에 없는 값
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\",\"reason\":\"NOT_A_REASON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OWNER 는 SecurityConfig 에서 403 으로 막힌다")
    void OWNER_403() throws Exception {
        // given
        Member owner = 회원_생성("owner1", MemberRole.OWNER);
        String token = jwtProvider.generateAccessToken(owner.getMemberId(), owner.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\",\"reason\":\"NOT_USING\"}"))
                .andExpect(status().isForbidden());

        assertThat(memberRepository.findById(owner.getMemberId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("탈퇴 직후 같은 토큰으로 내 정보를 조회하면 401 을 받는다")
    void 탈퇴후_같은토큰_401() throws Exception {
        // given
        Member member = 회원_생성("chulsoo", MemberRole.CUSTOMER);
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\",\"reason\":\"NOT_USING\"}"))
                .andExpect(status().isOk());

        // when & then
        mockMvc.perform(get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private Member 회원_생성(String userId, MemberRole role) {
        return memberRepository.save(Member.builder()
                .userId(userId).password(passwordEncoder.encode("pw1234!")).name("김철수")
                .provider(AuthProvider.LOCAL).role(role).build());
    }
}
