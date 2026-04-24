package com.whale.order.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 결제 상태 변경 이력 Entity.
 * OrderStatusHistory와 동일하게 append-only로 관리한다.
 * Saga 패턴에서 보상 트랜잭션 흐름을 추적하는 데 활용된다.
 */
@Entity
@Table(name = "payment_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentHistoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // 상태 변경 사유 (실패 메시지, 취소 사유 등)
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @Builder
    public PaymentHistory(Payment payment, PaymentStatus status, String reason) {
        this.payment = payment;
        this.status = status;
        this.reason = reason;
        this.changedAt = LocalDateTime.now();
    }
}
