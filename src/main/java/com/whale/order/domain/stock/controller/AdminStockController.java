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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 재고 관리 API.
 *
 * <p>ADMIN·OWNER 접근 가능. OWNER는 본인 매장 재고만 조회·설정할 수 있으며,
 * 권한 범위 검증은 서비스 레이어({@code StockService})에서 수행한다.</p>
 *
 * <p>재고 설정 시 {@code quantity}를 {@code -1} 또는 {@code null}로 지정하면
 * 무제한 재고로 처리되어 품절 없이 주문이 가능하다.</p>
 */
@Tag(name = "재고 (관리자)", description = "매장별 재고 조회 · 설정 · 복구 실패 목록")
@RestController
@RequestMapping("/api/admin/stores/{storeId}/stocks")
@RequiredArgsConstructor
public class AdminStockController {

    private final StockService stockService;

    /**
     * 매장의 전체 메뉴 재고 목록을 조회한다.
     *
     * <p>OWNER는 본인 매장만 조회 가능하다.</p>
     *
     * @param storeId     조회할 매장 ID
     * @param userDetails 인증된 관리자 정보
     * @return 메뉴별 재고 목록
     */
    @Operation(summary = "매장 재고 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockResponse>>> getStocks(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getStocks(storeId, callerId)));
    }

    /**
     * 매장의 재고 복구 실패 목록을 조회한다.
     *
     * <p>주문 취소·결제 실패 시 재고 복구가 3회 재시도 후에도 실패하면
     * {@code StockRestoreFailure}에 기록되고 어드민 SSE로 알림이 전송된다.
     * SSE 알림을 놓쳤을 때 이 엔드포인트로 직접 확인할 수 있다.</p>
     *
     * @param storeId     조회할 매장 ID
     * @param userDetails 인증된 관리자 정보
     * @return 재고 복구 실패 목록
     */
    @Operation(summary = "재고 복구 실패 목록 조회", description = "Kafka DLT 처리 중 재고 복구에 실패한 건. SSE를 놓쳤을 때 관리자가 직접 확인")
    @GetMapping("/restore-failures")
    public ResponseEntity<ApiResponse<List<StockRestoreFailureResponse>>> getRestoreFailures(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", stockService.getRestoreFailures(storeId, callerId)));
    }

    /**
     * 특정 메뉴의 재고를 설정한다 (upsert).
     *
     * <p>재고 레코드가 없으면 생성하고, 있으면 수량을 갱신한다.
     * {@code quantity}를 {@code -1} 또는 {@code null}로 지정하면 무제한 재고로 설정된다.</p>
     *
     * @param storeId     재고를 설정할 매장 ID
     * @param menuId      재고를 설정할 메뉴 ID
     * @param request     설정할 재고 수량
     * @param userDetails 인증된 관리자 정보
     * @return 설정된 재고 정보
     */
    @Operation(summary = "재고 설정", description = "특정 메뉴의 재고를 설정(upsert). 없으면 생성, 있으면 갱신")
    @PutMapping("/{menuId}")
    public ResponseEntity<ApiResponse<StockResponse>> setStock(
            @PathVariable Long storeId,
            @PathVariable Long menuId,
            @RequestBody StockUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("재고가 설정됐습니다", stockService.setStock(storeId, menuId, request, callerId)));
    }
}
