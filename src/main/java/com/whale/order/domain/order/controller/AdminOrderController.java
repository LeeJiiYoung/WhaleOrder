package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.domain.order.service.OrderSseService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Tag(name = "주문 (관리자)", description = "주문 목록 조회 · 상태 변경 · SSE 실시간 알림")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderSseService orderSseService;

    @Operation(summary = "주문 목록 조회", description = "상태 필터 복수 가능 (PENDING, PREPARING, COMPLETED, CANCELLED)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrders(
            @RequestParam(required = false) List<OrderStatus> statuses,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getAllOrders(statuses, callerId)));
    }

    @Hidden
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNewOrders() {
        return orderSseService.registerAdmin(UUID.randomUUID().toString());
    }

    @Operation(summary = "주문 상태 변경", description = "action: prepare(제조 시작) · complete(완료)")
    @PatchMapping("/{orderId}/{action}")
    public ResponseEntity<ApiResponse<OrderResponse>> changeStatus(
            @PathVariable Long orderId,
            @PathVariable String action,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long adminId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("상태가 변경됐습니다", orderService.changeStatus(orderId, action, adminId)));
    }
}
