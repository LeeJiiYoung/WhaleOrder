package com.whale.order.domain.order.entity;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 주문 항목 Entity.
 * options 필드는 주문 당시 선택한 옵션을 JSONB로 스냅샷 저장한다.
 * 이후 메뉴 옵션이 변경/삭제되어도 주문 내역이 보존된다.
 *
 * options 예시:
 * [{"group":"SIZE","name":"GRANDE","additionalPrice":500},
 *  {"group":"SHOT","name":"2샷","additionalPrice":0}]
 */
@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Orders orders;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(nullable = false)
    private Integer quantity;

    // 주문 당시 가격 - 이후 메뉴 가격이 변경되어도 영향받지 않음
    @Column(nullable = false)
    private Integer unitPrice;

    // 선택 옵션 스냅샷 - JSON 배열 형태로 저장
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String options;

    @Builder
    public OrderItem(Orders orders, Menu menu, Integer quantity,
                     Integer unitPrice, String options) {
        this.orders = orders;
        this.menu = menu;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.options = options;
    }

    // 주문 항목 소계
    public int getSubTotal() {
        return this.unitPrice * this.quantity;
    }
}
