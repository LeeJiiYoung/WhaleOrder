package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.LoginRequest;
import com.whale.order.domain.member.dto.LoginResponse;
import com.whale.order.domain.member.dto.RefreshRequest;
import com.whale.order.domain.member.dto.SignUpRequest;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로컬 회원가입/로그인 및 JWT 토큰 발급·갱신·폐기를 담당하는 컨트롤러.
 * 카카오 소셜 로그인은 OAuth2 리다이렉트 흐름으로 별도 처리된다.
 */
@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 갱신")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    /**
     * 로컬 회원가입을 처리하고, 가입 즉시 Access/Refresh Token을 발급한다.
     */
    @Operation(summary = "회원가입", description = "가입 즉시 토큰 발급")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<LoginResponse>> signUp(@Valid @RequestBody SignUpRequest request) {
        LoginResponse response = memberService.signUp(request);
        return ResponseEntity.ok(ApiResponse.ok("회원가입이 완료됐습니다", response));
    }

    /**
     * 아이디/비밀번호로 로그인하고 Access Token + Refresh Token을 발급한다.
     */
    @Operation(summary = "로그인", description = "Access Token + Refresh Token 발급")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = memberService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("로그인 성공", response));
    }

    /**
     * Refresh Token Rotation(RTR) — 전달된 Refresh Token으로 새 Access/Refresh Token을 발급하고
     * 기존 Refresh Token은 즉시 폐기한다.
     */
    @Operation(summary = "토큰 갱신 (RTR)", description = "Refresh Token으로 새 Access Token + Refresh Token 발급, 기존 토큰 폐기")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        LoginResponse response = memberService.refresh(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok("토큰 갱신 성공", response));
    }

    /**
     * Redis에 저장된 Refresh Token을 즉시 삭제해 로그아웃 처리한다.
     */
    @Operation(summary = "로그아웃", description = "Redis에서 Refresh Token 즉시 삭제")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        memberService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok("로그아웃 성공", null));
    }
}