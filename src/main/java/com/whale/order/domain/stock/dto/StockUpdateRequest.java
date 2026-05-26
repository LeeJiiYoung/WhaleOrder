package com.whale.order.domain.stock.dto;

// quantity = null 또는 -1 → 무제한, 0 이상 → 실제 재고 수량
public record StockUpdateRequest(Integer quantity) {
    public int resolvedQuantity() {
        return (quantity == null || quantity < 0) ? -1 : quantity;
    }
}
