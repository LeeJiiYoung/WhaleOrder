package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.MemberResponse;
import com.whale.order.domain.member.dto.MyProfileUpdateRequest;
import com.whale.order.domain.member.dto.PasswordChangeRequest;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // 내 정보 조회
    @GetMapping
    public ResponseEntity<ApiResponse<MemberResponse>> getMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long memberId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.getMyProfile(memberId)));
    }

    // 내 정보 수정 (닉네임, 전화번호)
    @PutMapping
    public ResponseEntity<ApiResponse<MemberResponse>> updateMyProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody MyProfileUpdateRequest request) {
        Long memberId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("정보가 수정됐습니다", memberService.updateMyProfile(memberId, request)));
    }

    // 비밀번호 변경
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PasswordChangeRequest request) {
        Long memberId = Long.parseLong(userDetails.getUsername());
        memberService.changePassword(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("비밀번호가 변경됐습니다", null));
    }
}
