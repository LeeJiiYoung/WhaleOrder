package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 실패 응답 검증.
 *
 * <p>MemberService.login 은 실패를 두 갈래로 던진다 — 아이디 미존재는 IllegalArgumentException,
 * 비밀번호 불일치는 Spring Security 의 BadCredentialsException. 후자에 핸들러가 없으면
 * catch-all 로 새서 500 "서버 오류가 발생했습니다" 가 나갔다.
 *
 * <p>두 분기는 같은 상태 코드·같은 문구여야 한다. 다르게 응답하면 어떤 아이디가 존재하는지
 * 알려주는 오라클이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class AuthLoginApiTest extends TestContainerBase {

    private static final String 실패_문구 = "아이디 또는 비밀번호가 올바르지 않습니다";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUpMember() {
        memberRepository.save(Member.builder()
                .userId("chulsoo").password(passwordEncoder.encode("pw1234!")).name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 500 이 아니라 400 과 실패 문구를 받는다")
    void 비밀번호_불일치_400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"chulsoo\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(실패_문구));
    }

    @Test
    @DisplayName("없는 아이디도 비밀번호 불일치와 똑같이 응답한다 — 계정 존재 여부가 드러나지 않는다")
    void 아이디_미존재_400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"no-such-user\",\"password\":\"pw1234!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(실패_문구));
    }

    @Test
    @DisplayName("올바른 자격증명이면 토큰을 발급받는다")
    void 로그인_성공_200() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"chulsoo\",\"password\":\"pw1234!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
