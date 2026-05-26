package com.whale.order.domain.order.dto;

import com.whale.order.domain.order.entity.Orders;

import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        Long orderId,
        String storeName,
        String memberNickname,
        String status,
        String orderType,
        Integer totalPrice,
        String customerRequest,
        List<OrderItemResponse> items,
        LocalDateTime orderedAt
) {
    public static OrderResponse from(Orders order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getStore().getName(),
                order.getMember().getNickname() != null ? order.getMember().getNickname() : order.getMember().getName(),
                order.getStatus().name(),
                order.getOrderType().name(),
                order.getTotalPrice(),
                order.getCustomerRequest(),
                order.getOrderItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt()
        );
    }
}
