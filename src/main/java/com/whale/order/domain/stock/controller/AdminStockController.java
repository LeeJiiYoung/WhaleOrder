package com.whale.order.domain.stock.controller;

import com.whale.order.domain.stock.dto.StockResponse;
import com.whale.order.domain.stock.dto.StockRestoreFailureResponse;
import com.whale.order.domain.stock.dto.StockUpdateRequest;
import com.whale.order.domain.stock.service.StockService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "재고 (관리자)", description = "매장별 재고 조회 · 설정 · 복구 실패 목록")
@RestController
@RequestMapping("/api/admin/stores/{storeId}/stocks")
@RequiredArgsConstructor
public class AdminStockController {

    private final StockService stockService;

    @Operation(summary = "매장 재고 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockResponse>>> getStocks(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getStocks(storeId)));
    }

    @Operation(summary = "재고 복구 실패 목록 조회", description = "Kafka DLT 처리 중 재고 복구에 실패한 건. SSE를 놓쳤을 때 관리자가 직접 확인")
    @GetMapping("/restore-failures")
    public ResponseEntity<ApiResponse<List<StockRestoreFailureResponse>>> getRestoreFailures() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getRestoreFailures()));
    }

    @Operation(summary = "재고 설정", description = "특정 메뉴의 재고를 설정(upsert). 없으면 생성, 있으면 갱신")
    @PutMapping("/{menuId}")
    public ResponseEntity<ApiResponse<StockResponse>> setStock(
            @PathVariable Long storeId,
            @PathVariable Long menuId,
            @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("재고가 설정됐습니다", stockService.setStock(storeId, menuId, request)));
    }
}
