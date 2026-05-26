package com.whale.order.domain.event.entity;

import com.whale.order.domain.store.entity.Store;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이벤트 한정 판매 굿즈 (텀블러 등 메뉴가 아닌 상품)
 */
@Entity
@Table(name = "goods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goods extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long goodsId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer price;

    private String imageUrl;

    @Builder
    public Goods(Store store, String name, String description, Integer price, String imageUrl) {
        this.store = store;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
    }
}
