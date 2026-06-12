package com.whale.order.domain.store.controller;

import com.whale.order.domain.menu.dto.StoreMenuResponse;
import com.whale.order.domain.menu.service.MenuService;
import com.whale.order.domain.store.dto.CustomerStoreResponse;
import com.whale.order.domain.store.service.StoreService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "매장 (고객)", description = "영업 중인 매장 목록 · 상세 조회")
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class CustomerStoreController {

    private final StoreService storeService;
    private final MenuService menuService;

    @Operation(summary = "영업 중인 매장 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerStoreResponse>>> getOpenStores() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getOpenStores()));
    }

    @Operation(summary = "매장 상세 조회", description = "영업 중 여부와 무관하게 조회 가능")
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<CustomerStoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getCustomerStore(storeId)));
    }

    @Operation(summary = "매장별 메뉴 + 재고 통합 조회",
               description = "판매 중인 메뉴와 해당 매장의 실시간 재고를 함께 반환. soldOut=true면 품절")
    @GetMapping("/{storeId}/menus")
    public ResponseEntity<ApiResponse<List<StoreMenuResponse>>> getStoreMenus(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getStoreMenus(storeId)));
    }
}
