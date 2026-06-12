package com.whale.order.domain.stock.controller;

import com.whale.order.domain.stock.dto.StockResponse;
import com.whale.order.domain.stock.service.StockService;
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

@Tag(name = "재고 (고객)", description = "매장별 메뉴 재고 조회 — 품절 여부 확인용")
@RestController
@RequestMapping("/api/stores/{storeId}/stocks")
@RequiredArgsConstructor
public class CustomerStockController {

    private final StockService stockService;

    @Operation(summary = "매장 재고 조회", description = "quantity=0이면 품절, null이면 무제한")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockResponse>>> getStocks(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getStocks(storeId)));
    }
}
