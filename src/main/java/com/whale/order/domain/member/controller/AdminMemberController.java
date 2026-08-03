package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.*;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자(ADMIN)용 회원 관리 컨트롤러. 회원 목록/단건 조회, 생성, 수정, 비밀번호 초기화를 제공한다.
 *
 * <p>회원 삭제는 제공하지 않는다. 역할 검사가 없어 점주·관리자까지 삭제돼 매장이 고아가 되는 문제가 있었고,
 * 탈퇴는 본인만 할 수 있도록 {@code DELETE /api/members/me} 로 일원화했다.
 * 제재가 필요하면 역할 변경(PUT)으로 처리한다.
 */
@Tag(name = "회원 (관리자)", description = "회원 목록 조회 · 생성 · 수정 · 비밀번호 초기화")
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;

    /**
     * 키워드(이름/닉네임 등)와 역할로 회원 목록을 필터링해 조회한다.
     */
    @Operation(summary = "회원 목록 조회", description = "키워드(이름/이메일) + 역할 필터 지원")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MemberResponse>>> getMembers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) MemberRole role) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.getMembers(keyword, role)));
    }

    /**
     * 회원 단건 정보를 조회한다.
     */
    @Operation(summary = "회원 단건 조회")
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> getMember(@PathVariable Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.getMember(memberId)));
    }

    /**
     * 관리자가 역할(ROLE)을 직접 지정해 회원을 생성한다.
     */
    @Operation(summary = "회원 생성", description = "관리자가 직접 회원을 생성. 역할(ROLE) 지정 가능")
    @PostMapping
    public ResponseEntity<ApiResponse<MemberResponse>> createMember(
            @Valid @RequestBody AdminMemberCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("회원이 생성됐습니다", memberService.createMember(request)));
    }

    /**
     * 회원의 닉네임, 전화번호, 역할을 수정한다.
     */
    @Operation(summary = "회원 수정", description = "닉네임, 전화번호, 역할 변경 가능")
    @PutMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> updateMember(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("회원 정보가 수정됐습니다", memberService.updateMember(memberId, request)));
    }

    /**
     * 회원 비밀번호를 임시 비밀번호(아이디를 두 번 이어붙인 값)로 초기화한다. 소셜 로그인 회원은 대상이 아니다.
     */
    @Operation(summary = "비밀번호 초기화", description = "임시 비밀번호(아이디×2)로 초기화")
    @PatchMapping("/{memberId}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@PathVariable Long memberId) {
        memberService.resetPassword(memberId);
        return ResponseEntity.ok(ApiResponse.ok("비밀번호가 초기화됐습니다", null));
    }

    /**
     * 매장 생성 시 오너로 지정할 OWNER 역할 회원을 키워드로 검색한다.
     */
    @Operation(summary = "OWNER 회원 검색", description = "매장 생성 시 오너 지정을 위한 검색 API")
    @GetMapping("/owners")
    public ResponseEntity<ApiResponse<List<MemberSearchResponse>>> searchOwners(
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.searchOwners(keyword)));
    }
}
