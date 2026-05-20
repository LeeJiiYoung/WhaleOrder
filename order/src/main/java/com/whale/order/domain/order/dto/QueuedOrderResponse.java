package com.whale.order.domain.order.dto;

public record QueuedOrderResponse(
        Long orderId,
        long position,
        String message
) {
    public static QueuedOrderResponse of(Long orderId, long position) {
        return new QueuedOrderResponse(
                orderId,
                position,
                position + "번째 대기 중입니다. 처리가 완료되면 알림을 드립니다."
        );
    }
}
