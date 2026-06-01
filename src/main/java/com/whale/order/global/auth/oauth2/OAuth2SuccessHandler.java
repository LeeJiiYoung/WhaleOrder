package com.whale.order.global.auth.oauth2;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.auth.jwt.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        log.info("OAuth2 로그인 성공 — principal 타입: {}", authentication.getPrincipal().getClass().getName());

        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        Member member = oAuth2User.getUserDetails().getMember();

        log.info("카카오 로그인 완료 — memberId={} role={}", member.getMemberId(), member.getRole());

        String accessToken = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());
        String refreshToken = jwtProvider.generateRefreshToken(member.getMemberId());

        // nickname은 URL에 포함하지 않음 — 한글 등 비ASCII 문자가 Tomcat Location 헤더를 깨뜨림
        // 프론트엔드는 로그인 후 /api/members/me 로 프로필을 조회할 것
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .queryParam("role", member.getRole().name())
                .build()
                .toUriString();

        log.info("리다이렉트 URL: {}", targetUrl);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
