package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.dto.MemberSearchResponse;
import com.whale.order.domain.member.service.MemberService;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;

    // OWNER 역할 회원 검색 (아이디 또는 이름)
    @GetMapping("/owners")
    public ResponseEntity<ApiResponse<List<MemberSearchResponse>>> searchOwners(
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", memberService.searchOwners(keyword)));
    }
}
