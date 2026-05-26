package com.whale.order.domain.event.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이벤트 구매 이력
 * event_id + member_id 유니크 제약으로 1인 1회 구매를 강제한다.
 */
@Entity
@Table(
    name = "event_purchase",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "member_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventPurchase extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventPurchaseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Builder
    public EventPurchase(Event event, Member member) {
        this.event = event;
        this.member = member;
    }
}
