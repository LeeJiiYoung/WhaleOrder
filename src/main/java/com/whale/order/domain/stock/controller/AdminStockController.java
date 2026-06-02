package com.whale.order.domain.stock.controller;

import com.whale.order.domain.stock.dto.StockResponse;
import com.whale.order.domain.stock.dto.StockRestoreFailureResponse;
import com.whale.order.domain.stock.dto.StockUpdateRequest;
import com.whale.order.domain.stock.repository.StockRestoreFailureRepository;
import com.whale.order.domain.stock.service.StockService;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stores/{storeId}/stocks")
@RequiredArgsConstructor
public class AdminStockController {

    private final StockService stockService;
    private final StockRestoreFailureRepository stockRestoreFailureRepository;

    // 매장 전체 재고 목록
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockResponse>>> getStocks(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getStocks(storeId)));
    }

    // 재고 복구 실패 목록 조회 — SSE를 놓쳤을 때 관리자가 직접 확인
    @GetMapping("/restore-failures")
    public ResponseEntity<ApiResponse<List<StockRestoreFailureResponse>>> getRestoreFailures() {
        List<StockRestoreFailureResponse> result = stockRestoreFailureRepository.findAll()
                .stream()
                .map(StockRestoreFailureResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", result));
    }

    // 특정 메뉴 재고 설정 (upsert)
    @PutMapping("/{menuId}")
    public ResponseEntity<ApiResponse<StockResponse>> setStock(
            @PathVariable Long storeId,
            @PathVariable Long menuId,
            @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("재고가 설정됐습니다", stockService.setStock(storeId, menuId, request)));
    }
}
