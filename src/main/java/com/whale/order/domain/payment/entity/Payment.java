package com.whale.order.domain.payment.entity;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 Entity.
 * 외부 PG사 API 연동 실패 시 Saga 패턴으로 보상 트랜잭션을 처리한다.
 * externalTxId는 PG사 취소 API 호출 시 사용하는 거래 식별자다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentId;

    // 주문당 결제는 하나만 존재
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Orders orders;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // PG사에서 발급한 거래 ID - Saga 보상 트랜잭션(취소) 시 필요
    private String externalTxId;

    // 결제 실패/취소 사유
    @Column(columnDefinition = "TEXT")
    private String failedReason;

    @Builder
    public Payment(Orders orders, Member member, Integer amount, PaymentMethod method) {
        this.orders = orders;
        this.member = member;
        this.amount = amount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
    }

    // PG사 결제 성공 콜백 처리
    public void success(String externalTxId) {
        this.status = PaymentStatus.SUCCESS;
        this.externalTxId = externalTxId;
    }

    // PG사 결제 실패 처리
    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failedReason = reason;
    }

    // Saga 보상 트랜잭션 - PG사 취소 API 호출 후 상태 변경
    public void cancel(String reason) {
        if (this.status != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("성공한 결제만 취소할 수 있습니다.");
        }
        this.status = PaymentStatus.CANCELLED;
        this.failedReason = reason;
    }
}
