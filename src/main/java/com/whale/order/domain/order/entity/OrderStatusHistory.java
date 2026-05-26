package com.whale.order.domain.order.entity;

import com.whale.order.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 상태 변경 이력 Entity.
 * BaseEntity를 상속하지 않는다. 이력 데이터는 수정/삭제 없이 append-only로 관리한다.
 * 실시간 SSE 푸시 이후 이력 추적 용도로 활용된다.
 */
@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Orders orders;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // 상태를 변경한 주체 (바리스타 또는 시스템 자동 처리 시 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private Member changedBy;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @Builder
    public OrderStatusHistory(Orders orders, OrderStatus status, Member changedBy) {
        this.orders = orders;
        this.status = status;
        this.changedBy = changedBy;
        this.changedAt = LocalDateTime.now();
    }
}
