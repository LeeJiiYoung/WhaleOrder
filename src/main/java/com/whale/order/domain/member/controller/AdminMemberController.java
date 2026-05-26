package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.*;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;

    // 전체 회원 목록 (키워드 + 역할 필터)
    @GetMapping
    public ResponseEntity<ApiResponse<List<MemberResponse>>> getMembers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) MemberRole role) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.getMembers(keyword, role)));
    }

    // 단건 조회
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> getMember(@PathVariable Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.getMember(memberId)));
    }

    // 회원 생성
    @PostMapping
    public ResponseEntity<ApiResponse<MemberResponse>> createMember(
            @Valid @RequestBody AdminMemberCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("회원이 생성됐습니다", memberService.createMember(request)));
    }

    // 회원 수정
    @PutMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> updateMember(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("회원 정보가 수정됐습니다", memberService.updateMember(memberId, request)));
    }

    // 회원 삭제
    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> deleteMember(@PathVariable Long memberId) {
        memberService.deleteMember(memberId);
        return ResponseEntity.ok(ApiResponse.ok("회원이 삭제됐습니다", null));
    }

    // OWNER 역할 회원 검색 (매장 생성 팝업용)
    @GetMapping("/owners")
    public ResponseEntity<ApiResponse<List<MemberSearchResponse>>> searchOwners(
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.searchOwners(keyword)));
    }
}
