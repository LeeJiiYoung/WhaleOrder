package com.whale.order.domain.payment.dto;

public record PaymentPrepareResponse(Long orderId,
                                     String tossOrderId,
                                     Long amount,
                                     String orderName) {
}
