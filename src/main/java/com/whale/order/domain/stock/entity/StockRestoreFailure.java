package com.whale.order.domain.stock.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_restore_failure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockRestoreFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long menuId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime failedAt;

    @Builder
    public StockRestoreFailure(Long orderId, Long storeId, Long menuId, int quantity, String reason) {
        this.orderId = orderId;
        this.storeId = storeId;
        this.menuId = menuId;
        this.quantity = quantity;
        this.reason = reason;
        this.failedAt = LocalDateTime.now();
    }
}
