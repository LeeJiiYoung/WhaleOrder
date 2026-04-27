package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.LoginRequest;
import com.whale.order.domain.member.dto.LoginResponse;
import com.whale.order.domain.member.dto.SignUpRequest;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<LoginResponse>> signUp(@Valid @RequestBody SignUpRequest request) {
        LoginResponse response = memberService.signUp(request);
        return ResponseEntity.ok(ApiResponse.ok("회원가입이 완료됐습니다", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = memberService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("로그인 성공", response));
    }
}