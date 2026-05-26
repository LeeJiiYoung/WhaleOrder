package com.whale.order.domain.event.entity;

import com.whale.order.domain.store.entity.Store;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 한정 판매 이벤트
 * 특정 매장에서 특정 굿즈를 선착순으로 판매한다.
 */
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_id", nullable = false)
    private Goods goods;

    @Column(nullable = false, length = 100)
    private String name;

    // 이벤트 오픈 시각
    @Column(nullable = false)
    private LocalDateTime openAt;

    // 총 판매 가능 수량
    @Column(nullable = false)
    private Integer capacity;

    // 1인당 구매 가능 수량
    @Column(nullable = false)
    private Integer perPersonLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    // 현재 남은 판매 수량 (오픈 시 capacity로 초기화, 구매 시 차감)
    @Column(nullable = false)
    private Integer remainingCapacity;

    @Builder
    public Event(Store store, Goods goods, String name, LocalDateTime openAt,
                 Integer capacity, Integer perPersonLimit) {
        this.store = store;
        this.goods = goods;
        this.name = name;
        this.openAt = openAt;
        this.capacity = capacity;
        this.perPersonLimit = perPersonLimit;
        this.status = EventStatus.SCHEDULED;
        this.remainingCapacity = 0;
    }

    public void open() {
        this.status = EventStatus.OPEN;
        this.remainingCapacity = this.capacity;
    }

    public void close() {
        this.status = EventStatus.CLOSED;
    }

    // 구매 시 재고 차감. 0이 되면 자동으로 이벤트 종료
    public void deductStock(int amount) {
        if (this.remainingCapacity < amount) {
            throw new IllegalStateException("이벤트 재고가 부족합니다 (남은 수량: " + this.remainingCapacity + ")");
        }
        this.remainingCapacity -= amount;
        if (this.remainingCapacity == 0) {
            this.status = EventStatus.CLOSED;
        }
    }
}
