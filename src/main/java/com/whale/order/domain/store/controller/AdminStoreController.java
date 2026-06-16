package com.whale.order.domain.store.controller;

import com.whale.order.domain.store.dto.StoreCreateRequest;
import com.whale.order.domain.store.dto.StoreUpdateRequest;
import com.whale.order.domain.store.dto.StoreResponse;
import com.whale.order.domain.store.service.StoreService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "매장 (관리자)", description = "매장 생성 · 목록 조회 · 영업 상태 변경")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreService storeService;

    @Operation(summary = "매장 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StoreResponse>>> getStores() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStores()));
    }

    @Operation(summary = "내 매장 목록 조회", description = "점주(OWNER) 본인 소유 매장만 조회 — 재고/주문 관리 진입점")
    @GetMapping("/my-stores")
    public ResponseEntity<ApiResponse<List<StoreResponse>>> getMyStores(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getMyStores(callerId)));
    }

    @Operation(summary = "매장 단건 조회")
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStore(storeId)));
    }

    @Operation(summary = "영업 시작", description = "매장 상태를 OPEN으로 변경")
    @PatchMapping("/{storeId}/open")
    public ResponseEntity<ApiResponse<StoreResponse>> openStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("영업을 시작합니다", storeService.openStore(storeId, callerId)));
    }

    @Operation(summary = "영업 종료", description = "매장 상태를 CLOSED로 변경")
    @PatchMapping("/{storeId}/close")
    public ResponseEntity<ApiResponse<StoreResponse>> closeStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("영업을 종료합니다", storeService.closeStore(storeId, callerId)));
    }

    @Operation(summary = "매장 수정")
    @PutMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> updateStore(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("매장 정보가 수정됐습니다", storeService.updateStore(storeId, request)));
    }

    @Operation(summary = "매장 생성")
    @PostMapping
    public ResponseEntity<ApiResponse<StoreResponse>> createStore(
            @Valid @RequestBody StoreCreateRequest request) {
        StoreResponse response = storeService.createStore(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("매장이 생성됐습니다", response));
    }
}
