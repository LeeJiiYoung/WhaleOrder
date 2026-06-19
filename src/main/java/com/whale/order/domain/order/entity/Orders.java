package com.whale.order.domain.order.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 주문 Entity. (테이블명: orders - 'order'는 PostgreSQL 예약어)
 * 주문 상태 변경 시 OrderStatusHistory에 이력을 남기고,
 * WebSocket/SSE를 통해 클라이언트에 실시간으로 상태를 푸시한다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Orders extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;

    // 낙관 락 — cancelOrder와 processOrder 동시 실행 시 lost update 방지
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private Integer totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType orderType;

    // 고객 요청사항 (없을 수 있음)
    @Column(length = 500)
    private String customerRequest;

    // Kafka Consumer가 재고 차감 완료 후 true로 설정
    // 주문 취소 시 이 값으로 재고 복구 여부를 결정 (Redis removeFromQueue 대체)
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean stockDeducted = false;

    // 주문 항목 - 주문과 함께 생성/삭제됨
    @OneToMany(mappedBy = "orders", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    @Builder
    public Orders(Member member, Store store, Integer totalPrice,
                  OrderType orderType, String customerRequest) {
        this.member = member;
        this.store = store;
        this.totalPrice = totalPrice;
        this.orderType = orderType;
        this.customerRequest = customerRequest;
        this.status = OrderStatus.PENDING; // 결제 완료 후 접수 대기 상태로 시작
    }

    public void addOrderItem(OrderItem orderItem) {
        this.orderItems.add(orderItem);
    }

    // 제조 시작
    public void startPreparing() {
        validateStatus(OrderStatus.PENDING);
        this.status = OrderStatus.PREPARING;
    }

    // 제조 완료
    public void complete() {
        validateStatus(OrderStatus.PREPARING);
        this.status = OrderStatus.COMPLETED;
    }

    // Kafka Consumer 처리 완료 후 호출
    public void markStockDeducted() {
        this.stockDeducted = true;
    }

    // 주문 취소 - 접수 대기(PENDING) 상태에서만 가능
    public void cancel() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("접수 대기 중인 주문만 취소할 수 있습니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }

    // 관리자 취소 - 접수 대기 / 제조 중 상태에서 가능 (완료·취소된 주문은 불가)
    public void cancelByAdmin() {
        if (this.status != OrderStatus.PENDING && this.status != OrderStatus.PREPARING) {
            throw new IllegalStateException("완료되었거나 이미 취소된 주문은 취소할 수 없습니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }

    private void validateStatus(OrderStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                String.format("주문 상태가 올바르지 않습니다. 현재: %s, 필요: %s", this.status, expected)
            );
        }
    }
}
