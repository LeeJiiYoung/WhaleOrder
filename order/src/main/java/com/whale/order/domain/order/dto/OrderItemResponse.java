package com.whale.order.domain.order.dto;

import com.whale.order.domain.order.entity.OrderItem;

public record OrderItemResponse(
        Long orderItemId,
        Long menuId,
        String menuName,
        Integer quantity,
        Integer unitPrice,
        Integer subTotal,
        String options   // JSON 스냅샷 그대로 전달
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getOrderItemId(),
                item.getMenu().getMenuId(),
                item.getMenu().getName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSubTotal(),
                item.getOptions()
        );
    }
}
