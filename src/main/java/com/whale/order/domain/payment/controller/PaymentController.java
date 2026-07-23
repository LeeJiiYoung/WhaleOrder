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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 API.
 *
 * <p>Mock PG를 통해 결제를 처리한다. 실제 외부 API 호출을 시뮬레이션하며
 * 90% 확률로 성공, 10% 확률로 실패를 반환한다.</p>
 *
 * <p>결제 성공 시 Kafka 대기열에 주문을 등록하고 재고 차감을 시작한다.
 * 결제 실패 시 Saga 보상 트랜잭션으로 주문을 자동 취소하고 재고를 복구한다.</p>
 */
@Slf4j
@Tag(name = "결제", description = "Mock PG 결제 처리 (90% 성공률) · Kafka로 주문 처리 연동")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Mock PG 결제를 처리한다.
     *
     * <p>90% 확률로 결제가 승인되며, 성공 시 Kafka 이벤트를 통해 재고 차감이 시작된다.
     * 결제 실패(10%) 시 주문이 {@code CANCELLED}로 전환되고 이미 차감된 재고가 복구된다.
     * 모든 결제 시도는 {@code PaymentHistory}에 기록된다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param request     주문 ID · 결제 수단 등 결제 정보
     * @return 결제 결과 (승인번호, 결제 금액 등)
     */
    @Operation(summary = "결제", description = "Mock PG 결제 → 성공 시 Kafka로 주문 처리 시작. 실패 시 Saga 보상 트랜잭션으로 주문 자동 취소")
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> pay(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        log.info("[컨트롤러] pay() 호출 시작 memberId={}", memberId);
        PaymentResponse response = paymentService.pay(memberId, request);
        log.info("[컨트롤러] pay() 리턴 받음 orderId={}", response.orderId());
        return ResponseEntity.ok(ApiResponse.ok("결제가 완료됐습니다", response));
    }

    /**
     * 주문에 연결된 결제 정보를 조회한다.
     *
     * <p>본인 주문의 결제 내역만 조회 가능하다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param orderId     조회할 주문 ID
     * @return 결제 상태 · 승인번호 · 결제 금액 등
     */
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
