package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    // 전체 주문 목록 (상태 필터)
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrders(
            @RequestParam(required = false) OrderStatus status) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getAllOrders(status)));
    }

    // 주문 상태 변경 (accept / prepare / complete)
    @PatchMapping("/{orderId}/{action}")
    public ResponseEntity<ApiResponse<OrderResponse>> changeStatus(
            @PathVariable Long orderId,
            @PathVariable String action,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long adminId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("상태가 변경됐습니다", orderService.changeStatus(orderId, action, adminId)));
    }
}
