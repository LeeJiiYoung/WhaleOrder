package com.whale.order.domain.payment.controller;

import com.whale.order.domain.payment.dto.PaymentInfoResponse;
import com.whale.order.domain.payment.dto.PaymentRequest;
import com.whale.order.domain.payment.dto.PaymentResponse;
import com.whale.order.domain.payment.service.PaymentService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // Mock 결제 처리 — 결제 성공 시 주문 생성 및 대기열 등록
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> pay(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentResponse response = paymentService.pay(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("결제가 완료됐습니다", response));
    }

    // 주문별 결제 정보 조회
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<PaymentInfoResponse>> getPaymentByOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentInfoResponse response = paymentService.getPaymentByOrder(orderId, memberId);
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", response));
    }
}
