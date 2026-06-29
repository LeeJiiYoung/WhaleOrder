package com.whale.order.domain.stock.controller;

import com.whale.order.domain.stock.dto.StockRestoreFailureResponse;
import com.whale.order.domain.stock.service.StockService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 전체 재고 복구 실패 목록 API (ADMIN 전용).
 *
 * <p>매장 구분 없이 시스템 전체의 재고 복구 실패 내역을 조회한다.
 * 매장별 조회는 {@code /api/admin/stores/{storeId}/stocks/restore-failures}를 사용한다.</p>
 *
 * <p>재고 복구 실패는 주문 취소·결제 실패 후 Redis 분산 락 경합 등으로
 * 3회 재시도가 모두 소진됐을 때 기록된다.</p>
 */
@Tag(name = "재고 복구 실패 (관리자)", description = "시스템 전체 재고 복구 실패 목록 조회 — Saga 보상 트랜잭션 실패 추적")
@RestController
@RequestMapping("/api/admin/stock-restore-failures")
@RequiredArgsConstructor
public class AdminStockRestoreFailureController {

    private final StockService stockService;

    /**
     * 시스템 전체 재고 복구 실패 목록을 조회한다.
     *
     * @return 재고 복구 실패 목록 (발생 시각 · 매장 · 메뉴 · 실패 사유 포함)
     */
    @Operation(summary = "전체 재고 복구 실패 목록 조회", description = "시스템 전체의 재고 복구 실패 내역을 조회한다. 매장별 조회는 /api/admin/stores/{storeId}/stocks/restore-failures를 사용한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockRestoreFailureResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getRestoreFailures()));
    }
}
