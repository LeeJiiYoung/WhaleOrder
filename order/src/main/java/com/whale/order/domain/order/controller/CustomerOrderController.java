package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderCreateRequest;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.global.idempotency.IdempotencyService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    // 주문 생성
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request) {

        if (idempotencyKey != null) {
            // 이미 완료된 요청이면 캐시된 응답 반환
            OrderResponse cached = idempotencyService.getResult(idempotencyKey, OrderResponse.class);
            if (cached != null) {
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.ok("주문이 완료됐습니다", cached));
            }
            // 다른 스레드가 동일 키를 처리 중이면 409
            if (!idempotencyService.markProcessing(idempotencyKey)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.fail("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
            }
        }

        try {
            OrderResponse response = orderService.createOrder(memberId(userDetails), request);
            if (idempotencyKey != null) {
                idempotencyService.saveResult(idempotencyKey, response);
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.ok("주문이 완료됐습니다", response));
        } catch (Exception e) {
            // 실패 시 키 삭제 → 클라이언트가 재시도 가능
            if (idempotencyKey != null) {
                idempotencyService.delete(idempotencyKey);
            }
            throw e;
        }
    }

    // 내 주문 목록
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyOrders(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getMyOrders(memberId(userDetails))));
    }

    // 주문 상세
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getOrder(orderId, memberId(userDetails))));
    }

    // 주문 취소
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
