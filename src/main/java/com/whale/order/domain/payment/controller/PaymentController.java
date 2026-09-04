package com.whale.order.domain.payment.controller;

import com.whale.order.domain.payment.dto.*;
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
 * <p>토스페이먼츠 결제위젯 연동. 결제창 호출 전 {@code prepare}로 주문·금액을 서버에 먼저
 * 저장해 orderId를 발급하고, 결제창에서 승인된 뒤 successUrl 리다이렉트로 받은 정보를
 * {@code confirm}에 넘겨 토스 결제승인 API를 호출해 최종 확정한다.</p>
 *
 * <p>결제 승인 성공 시 Kafka 대기열에 주문을 등록하고 재고 차감을 시작한다.
 * 결제 승인 실패 시 Saga 보상 트랜잭션으로 주문을 자동 취소하고 재고를 복구한다.</p>
 */
@Slf4j
@Tag(name = "결제", description = "토스페이먼츠 결제위젯 연동 (prepare → confirm) · Kafka로 주문 처리 연동")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

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

    @Operation(summary = "결제 준비", description = "결제창 호출 전 주문·금액을 서버에 임시 저장하고 orderId를 발급")
    @PostMapping("/prepare")
    public ResponseEntity<ApiResponse<PaymentPrepareResponse>> prepare(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentPrepareRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentPrepareResponse response = paymentService.prepare(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("주문이 준비됐습니다", response));
    }

    /**
     * 토스 결제 승인(confirm) 처리.
     *
     * <p>successUrl 리다이렉트로 받은 paymentKey·orderId·amount를 그대로 넘겨받아,
     * prepare 때 저장해둔 금액과 대조 검증한 뒤 토스 결제승인 API를 호출한다.
     * 승인 성공 시에만 결제·주문이 최종 확정되며, Kafka로 주문 처리가 시작된다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param request     paymentKey · orderId · amount
     * @return 결제 결과 (승인번호, 결제 수단, 결제 금액 등)
     */
    @Operation(summary = "결제 승인", description = "토스 결제창 successUrl 리다이렉트 후 최종 승인 확정 → 성공 시 Kafka로 주문 처리 시작")
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirm(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentConfirmRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        PaymentResponse response = paymentService.confirm(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("결제가 완료됐습니다", response));
    }

    /**
     * 결제 대기(AWAITING_PAYMENT) 주문 정리.
     *
     * <p>토스 결제창이 취소·거절되어 confirm까지 가지 못한 경우, prepare()가 만든 임시 주문을
     * 클라이언트가 능동적으로 정리하기 위해 호출한다. 이미 확정됐거나 이미 정리된 주문이면
     * 아무 일도 하지 않으므로 여러 번 호출해도 안전하다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param request     토스 전용 orderId ("whale-17" 형식)
     */
    @Operation(summary = "결제 대기 주문 정리", description = "토스 결제창 취소/거절로 confirm까지 못 간 주문을 CANCELLED로 정리 (멱등)")
    @PostMapping("/cancel-pending")
    public ResponseEntity<ApiResponse<Void>> cancelPending(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody PaymentCancelPendingRequest request) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        paymentService.cancelAwaitingPayment(memberId, request.orderId(), "토스 결제창 취소/거절");
        return ResponseEntity.ok(ApiResponse.ok("정리됐습니다"));
    }
}
