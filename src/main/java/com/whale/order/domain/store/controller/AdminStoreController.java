package com.whale.order.domain.store.controller;

import com.whale.order.domain.store.dto.StoreCreateRequest;
import com.whale.order.domain.store.dto.StoreResponse;
import com.whale.order.domain.store.service.StoreService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final StoreService storeService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<StoreResponse>>> getStores() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStores()));
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getStore(storeId)));
    }

    @PatchMapping("/{storeId}/open")
    public ResponseEntity<ApiResponse<StoreResponse>> openStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("영업을 시작합니다", storeService.openStore(storeId)));
    }

    @PatchMapping("/{storeId}/close")
    public ResponseEntity<ApiResponse<StoreResponse>> closeStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("영업을 종료합니다", storeService.closeStore(storeId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StoreResponse>> createStore(
            @Valid @RequestBody StoreCreateRequest request) {
        StoreResponse response = storeService.createStore(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("매장이 생성됐습니다", response));
    }
}
