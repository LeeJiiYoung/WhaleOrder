package com.whale.order.domain.stock.dto;

import com.whale.order.domain.stock.entity.StockRestoreFailure;

import java.time.LocalDateTime;

public record StockRestoreFailureResponse(
        Long id,
        Long orderId,
        Long storeId,
        Long menuId,
        int quantity,
        String reason,
        LocalDateTime failedAt
) {
    public static StockRestoreFailureResponse from(StockRestoreFailure failure) {
        return new StockRestoreFailureResponse(
                failure.getId(),
                failure.getOrderId(),
                failure.getStoreId(),
                failure.getMenuId(),
                failure.getQuantity(),
                failure.getReason(),
                failure.getFailedAt()
        );
    }
}
