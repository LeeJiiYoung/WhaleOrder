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

/**
 * 고객용 재고 조회 API.
 *
 * <p>주문 전 품절 여부를 확인하기 위한 읽기 전용 엔드포인트다.
 * {@code quantity=0}이면 품절, {@code null}이면 무제한 판매 중을 의미한다.</p>
 *
 * <p>메뉴와 재고를 한 번에 조회하려면
 * {@code GET /api/stores/{storeId}/menus}를 사용한다 ({@code soldOut} 필드 포함).</p>
 */
@Tag(name = "재고 (고객)", description = "매장별 메뉴 재고 조회 — 품절 여부 확인용")
@RestController
@RequestMapping("/api/stores/{storeId}/stocks")
@RequiredArgsConstructor
public class CustomerStockController {

    private final StockService stockService;

    /**
     * 매장의 메뉴별 재고를 조회한다.
     *
     * <p>{@code quantity=0}이면 품절, {@code null}이면 무제한이다.</p>
     *
     * @param storeId 조회할 매장 ID
     * @return 메뉴별 재고 목록
     */
    @Operation(summary = "매장 재고 조회", description = "quantity=0이면 품절, null이면 무제한")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockResponse>>> getStocks(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getStocks(storeId)));
    }
}
