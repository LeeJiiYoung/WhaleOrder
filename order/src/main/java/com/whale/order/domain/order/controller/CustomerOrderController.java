package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderCreateRequest;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.dto.QueuedOrderResponse;
import com.whale.order.domain.order.service.OrderService;
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

    // 주문 생성 → 즉시 대기 순서 반환, 결과는 SSE로 수신
    @PostMapping
    public ResponseEntity<ApiResponse<QueuedOrderResponse>> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody OrderCreateRequest request) {
        QueuedOrderResponse response = orderService.createOrder(memberId(userDetails), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("주문이 대기열에 등록됐습니다", response));
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
