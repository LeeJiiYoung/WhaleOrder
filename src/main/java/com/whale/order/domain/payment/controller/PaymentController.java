package com.whale.order.domain.payment.controller;

import com.whale.order.domain.payment.dto.PaymentInfoResponse;
import com.whale.order.domain.payment.dto.PaymentRequest;
import com.whale.order.domain.payment.dto.PaymentResponse;
import com.whale.order.domain.payment.service.PaymentService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Tag(name = "결제", description = "Mock PG 결제 처리 (90% 성공률) · Kafka로 주문 처리 연동")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제", description = "Mock PG 결제 → 성공 시 Kafka로 주문 처리 시작. 실패 시 Saga 보상 트랜잭션으로 주문 자동 취소")
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> pay(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentResponse response = paymentService.pay(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("결제가 완료됐습니다", response));
    }

    @Operation(summary = "주문별 결제 정보 조회")
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<PaymentInfoResponse>> getPaymentByOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentInfoResponse response = paymentService.getPaymentByOrder(orderId, memberId);
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", response));
    }
}
