package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.payment.entity.Payment;

public record PaymentResponse(
        Long paymentId,
        Long orderId,
        Integer amount,
        String method,
        String status,
        String externalTxId,
        long queuePosition
) {
    public static PaymentResponse from(Payment payment, Orders order, long queuePosition) {
        return new PaymentResponse(
                payment.getPaymentId(),
                order.getOrderId(),
                payment.getAmount(),
                payment.getMethod().name(),
                payment.getStatus().name(),
                payment.getExternalTxId(),
                queuePosition
        );
    }
}
