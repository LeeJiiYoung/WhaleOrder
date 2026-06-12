package com.whale.order.domain.stock.controller;

import com.whale.order.domain.stock.dto.StockRestoreFailureResponse;
import com.whale.order.domain.stock.service.StockService;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stock-restore-failures")
@RequiredArgsConstructor
public class AdminStockRestoreFailureController {

    private final StockService stockService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<StockRestoreFailureResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getRestoreFailures()));
    }
}
