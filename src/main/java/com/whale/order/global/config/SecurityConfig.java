package com.whale.order.global.config;

import com.whale.order.global.auth.CustomUserDetailsService;
import com.whale.order.global.auth.jwt.JwtAuthenticationFilter;
import com.whale.order.global.auth.jwt.JwtProvider;
import com.whale.order.global.auth.oauth2.KakaoOAuth2UserService;
import com.whale.order.global.auth.oauth2.OAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService userDetailsService;
    private final KakaoOAuth2UserService kakaoOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // OAuth2 인가 코드 플로우는 state 저장을 위해 세션이 필요 → IF_REQUIRED 사용
                // JWT 필터가 API 요청은 stateless하게 처리하므로 실질적으로 무상태 유지
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 폼 로그인 비활성화 — oauth2Login()이 DefaultLoginPageGeneratingFilter를 추가해
                // 인증 실패 시 /login 으로 리다이렉트하는 것을 차단
                .formLogin(AbstractHttpConfigurer::disable)
                // 인증 실패 시 /login 리다이렉트 대신 401 반환 (REST API 서버)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // Swagger UI
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 카카오 OAuth2 경로 (9단계에서 추가)
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // 업로드된 이미지 파일 (인증 없이 접근 가능)
                        .requestMatchers("/uploads/**").permitAll()
                        // 고객용 조회 API (로그인 없이 메뉴/매장 탐색 가능)
                        .requestMatchers("/api/stores/**", "/api/menus/**").permitAll()
                        // 정적 어드민 페이지 (역할 체크는 클라이언트에서)
                        .requestMatchers("/admin/**").permitAll()
                        // 분산락 데모 페이지
                        .requestMatchers("/demo/**", "/stock-demo.html", "/queue-demo.html").permitAll()
                        // 관리자 API - ADMIN 역할만 허용
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(kakaoOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler((req, res, ex) -> {
                            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class)
                                .error("OAuth2 로그인 실패: {}", ex.getMessage(), ex);
                            res.sendRedirect("/login?error=" + ex.getMessage());
                        }))
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtProvider, userDetailsService);
    }

    // 자체 로그인 시 비밀번호 검증에 사용
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}