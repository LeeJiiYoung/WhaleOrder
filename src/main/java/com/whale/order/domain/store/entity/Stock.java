package com.whale.order.domain.store.entity;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매장별 메뉴 재고 Entity.
 * 동시 주문 시 재고 차감 문제를 방지하기 위해 Redis 분산 락 또는 비관적 락을 적용한다.
 * 재고가 0이면 해당 매장에서 해당 메뉴 주문 불가.
 */
@Entity
@Table(
    name = "stock",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock",
        columnNames = {"store_id", "menu_id"}
    )
)
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

    @Column(nullable = false)
    private Integer quantity;

    @Builder
    public Stock(Store store, Menu menu, Integer quantity) {
        this.store = store;
        this.menu = menu;
        this.quantity = quantity;
    }

    /**
     * 주문 시 재고 차감.
     * 재고 부족 시 예외 발생 - 호출 전 분산 락 획득 필수.
     */
    public void decrease(int amount) {
        if (this.quantity < amount) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.quantity -= amount;
    }

    // 재고 입고
    public void increase(int amount) {
        this.quantity += amount;
    }
}
