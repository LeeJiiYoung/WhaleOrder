package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.service.OrderService;
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
 * 고객 주문 API.
 *
 * <p>주문 생성은 결제 API({@code POST /api/payments})에서 처리한다.
 * 이 컨트롤러는 주문 조회/상세/취소만 담당한다.</p>
 */
@Tag(name = "주문 (고객)", description = "주문 조회 · 상세 · 취소")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;

    /**
     * 본인의 주문 목록을 조회한다.
     *
     * @param userDetails 인증된 회원 정보
     * @return 주문 목록 (최신순)
     */
    @Operation(summary = "내 주문 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyOrders(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getMyOrders(memberId(userDetails))));
    }

    /**
     * 주문 상세 정보를 조회한다.
     *
     * <p>본인 주문만 조회 가능하며, 타인의 주문 ID 입력 시 예외가 발생한다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param orderId     조회할 주문 ID
     * @return 주문 상세 (주문 항목 · 옵션 · 상태 포함)
     */
    @Operation(summary = "주문 상세 조회")
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getOrder(orderId, memberId(userDetails))));
    }

    /**
     * 주문을 취소한다.
     *
     * <p>PENDING 상태에서만 취소 가능하다. 재고 차감이 완료된 주문은 재고를 복구하며,
     * 복구 실패 시 {@code StockRestoreFailure}에 기록되고 어드민에 SSE 알림이 전송된다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param orderId     취소할 주문 ID
     * @return 취소된 주문 정보
     */
    @Operation(summary = "주문 취소", description = "PENDING 상태에서만 가능 · 재고 복구 포함")
    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok("주문이 취소됐습니다", orderService.cancelOrder(orderId, memberId(userDetails))));
    }

    private Long memberId(UserDetails u) {
        return Long.parseLong(u.getUsername());
    }
}
