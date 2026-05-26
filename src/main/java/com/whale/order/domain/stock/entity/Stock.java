package com.whale.order.domain.stock.entity;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "menu_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long stockId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    // -1 = 무제한, 0 이상 = 실제 재고
    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public Stock(Store store, Menu menu, Integer quantity) {
        this.store = store;
        this.menu = menu;
        this.quantity = quantity;
    }

    // 재고 차감 (무제한이면 skip)
    public void deduct(int amount) {
        if (this.quantity < 0) return;
        if (this.quantity < amount) {
            throw new IllegalStateException(
                    menu.getName() + " 재고가 부족합니다 (남은 재고: " + this.quantity + "개)");
        }
        this.quantity -= amount;
    }

    // 주문 취소 시 재고 복구
    public void restore(int amount) {
        if (this.quantity < 0) return;
        this.quantity += amount;
    }

    public void updateQuantity(int quantity) {
        this.quantity = quantity;
    }
}
