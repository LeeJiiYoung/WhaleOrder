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

/**
 * 관리자 매장 관리 API.
 *
 * <p>ADMIN은 전체 매장을 관리하고, OWNER는 본인 소유 매장만 조회·운영할 수 있다.
 * 영업 상태(OPEN/CLOSED)는 고객 화면의 매장 목록 노출 여부에 영향을 준다.</p>
 */
@Tag(name = "매장 (관리자)", description = "매장 생성 · 목록 조회 · 영업 상태 변경")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreService storeService;

    /**
     * 전체 매장 목록을 조회한다 (ADMIN 전용).
     *
     * @return 전체 매장 목록
     */
    @Operation(summary = "매장 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StoreResponse>>> getStores() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStores()));
    }

    /**
     * 점주 본인 소유 매장 목록을 조회한다 (OWNER 전용).
     *
     * <p>OWNER가 재고 관리·주문 처리 화면으로 진입할 때 사용하는 진입점이다.</p>
     *
     * @param userDetails 인증된 점주 정보
     * @return 본인 소유 매장 목록
     */
    @Operation(summary = "내 매장 목록 조회", description = "점주(OWNER) 본인 소유 매장만 조회 — 재고/주문 관리 진입점")
    @GetMapping("/my-stores")
    public ResponseEntity<ApiResponse<List<StoreResponse>>> getMyStores(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getMyStores(callerId)));
    }

    /**
     * 매장 단건을 조회한다.
     *
     * @param storeId 조회할 매장 ID
     * @return 매장 정보
     */
    @Operation(summary = "매장 단건 조회")
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStore(storeId)));
    }

    /**
     * 매장 영업을 시작한다 (CLOSED → OPEN).
     *
     * <p>OPEN 상태가 되면 고객 화면의 매장 목록에 노출된다.</p>
     *
     * @param storeId     영업 시작할 매장 ID
     * @param userDetails 인증된 관리자 정보
     * @return 변경된 매장 정보
     */
    @Operation(summary = "영업 시작", description = "매장 상태를 OPEN으로 변경")
    @PatchMapping("/{storeId}/open")
    public ResponseEntity<ApiResponse<StoreResponse>> openStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("영업을 시작합니다", storeService.openStore(storeId, callerId)));
    }

    /**
     * 매장 영업을 종료한다 (OPEN → CLOSED).
     *
     * <p>CLOSED 상태가 되면 고객 화면의 매장 목록에서 제외된다.</p>
     *
     * @param storeId     영업 종료할 매장 ID
     * @param userDetails 인증된 관리자 정보
     * @return 변경된 매장 정보
     */
    @Operation(summary = "영업 종료", description = "매장 상태를 CLOSED로 변경")
    @PatchMapping("/{storeId}/close")
    public ResponseEntity<ApiResponse<StoreResponse>> closeStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("영업을 종료합니다", storeService.closeStore(storeId, callerId)));
    }

    /**
     * 매장 정보를 수정한다.
     *
     * @param storeId 수정할 매장 ID
     * @param request 수정할 매장 정보
     * @return 수정된 매장 정보
     */
    @Operation(summary = "매장 수정")
    @PutMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> updateStore(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("매장 정보가 수정됐습니다", storeService.updateStore(storeId, request)));
    }

    /**
     * 새 매장을 생성한다.
     *
     * @param request 매장명·주소·전화번호 등 생성 정보
     * @return 생성된 매장 정보 (HTTP 201)
     */
    @Operation(summary = "매장 생성")
    @PostMapping
    public ResponseEntity<ApiResponse<StoreResponse>> createStore(
            @Valid @RequestBody StoreCreateRequest request) {
        StoreResponse response = storeService.createStore(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("매장이 생성됐습니다", response));
    }
}
