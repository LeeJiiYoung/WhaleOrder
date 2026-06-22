package com.whale.order.domain.payment.dto;

import com.whale.order.domain.payment.entity.Payment;

public record PaymentInfoResponse(
        Long paymentId,
        String method,
        String methodLabel,
        Long amount,
        String status,
        String statusLabel,
        String externalTxId
) {
    private static final java.util.Map<String, String> METHOD_LABELS = java.util.Map.of(
            "CREDIT_CARD", "신용/체크카드",
            "KAKAO_PAY",   "카카오페이",
            "NAVER_PAY",   "네이버페이"
    );
    private static final java.util.Map<String, String> STATUS_LABELS = java.util.Map.of(
            "PENDING",   "결제 진행 중",
            "SUCCESS",   "결제 완료",
            "FAILED",    "결제 실패",
            "CANCELLED", "결제 취소(환불)"
    );

    public static PaymentInfoResponse from(Payment p) {
        return new PaymentInfoResponse(
                p.getPaymentId(),
                p.getMethod().name(),
                METHOD_LABELS.getOrDefault(p.getMethod().name(), p.getMethod().name()),
                p.getAmount(),
                p.getStatus().name(),
                STATUS_LABELS.getOrDefault(p.getStatus().name(), p.getStatus().name()),
                p.getExternalTxId()
        );
    }
}
