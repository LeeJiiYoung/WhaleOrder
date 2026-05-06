package com.whale.order.domain.store.controller;

import com.whale.order.domain.store.dto.CustomerStoreResponse;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.entity.StoreStatus;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class CustomerStoreController {

    private final StoreRepository storeRepository;

    // 영업 중인 매장 목록
    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerStoreResponse>>> getOpenStores() {
        List<CustomerStoreResponse> stores = storeRepository.findAllOpenStores().stream()
                .map(CustomerStoreResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stores));
    }

    // 매장 상세 (영업 중 여부와 무관하게 조회 가능)
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<CustomerStoreResponse>> getStore(@PathVariable Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다: " + storeId));
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", CustomerStoreResponse.from(store)));
    }
}
