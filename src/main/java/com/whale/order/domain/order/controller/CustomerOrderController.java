package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderCreateRequest;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.dto.QueuedOrderResponse;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 고객 주문 API.
 *
 * <p>주문 생성 시 장바구니를 기반으로 주문을 DB에 저장하고 Kafka 대기열에 등록한다.
 * 재고 차감은 Kafka Consumer가 비동기로 처리하며, 결과는 SSE({@code /api/orders/{orderId}/result})로 수신한다.
 * 멱등성 키로 중복 주문을 방지하며, 주문 취소 시 재고가 자동 복구된다.</p>
 */
@Tag(name = "주문 (고객)", description = "주문 생성 · 조회 · 취소")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;

    /**
     * 장바구니 기반으로 주문을 생성하고 Kafka 대기열에 등록한다.
     *
     * <p>DB 커밋 이후 {@code @TransactionalEventListener}를 통해 Kafka 메시지를 발행하므로
     * 커밋 실패 시 메시지 유실이 발생하지 않는다.
     * 재고 차감 결과는 SSE({@code /api/orders/{orderId}/result})로 푸시된다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param request     매장 ID · 주문 유형 · 요청 사항
     * @return 주문 ID 및 대기 순서 (HTTP 202)
     */
    @Operation(summary = "주문 생성", description = "Redis 분산 락으로 동시성 제어 · 재고 차감 결과는 SSE(/api/orders/{orderId}/result)로 수신")
    @PostMapping
    public ResponseEntity<ApiResponse<QueuedOrderResponse>> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody OrderCreateRequest request) {
        QueuedOrderResponse response = orderService.createOrder(memberId(userDetails), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("주문이 대기열에 등록됐습니다", response));
    }

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
