package com.whale.order.global.auth.oauth2;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.auth.RefreshTokenService;
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
    private final RefreshTokenService refreshTokenService;

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
        refreshTokenService.save(member.getMemberId(), refreshToken);

        // nickname은 URL에 포함하지 않음 — 한글 등 비ASCII 문자가 Tomcat Location 헤더를 깨뜨림
        // 프론트엔드는 로그인 후 /api/members/me 로 프로필을 조회할 것
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .queryParam("role", member.getRole().name())
                .build()
                .toUriString();

        // targetUrl 에 accessToken/refreshToken 이 query 로 박혀 있어 평문 로깅 금지 —
        // 로그 수집 시스템(Loki/CloudWatch/ELK) 에 토큰 노출되면 사실상 계정 인수 가능.
        // 호스트·경로 만 남기고 식별자(memberId/role) 로 흐름 추적.
        log.info("OAuth2 콜백 리다이렉트 memberId={} role={} → {}",
                member.getMemberId(), member.getRole(), redirectUri);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
