package com.whale.order.domain.order.service;

import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.payment.entity.PaymentHistory;
import com.whale.order.domain.payment.entity.PaymentStatus;
import com.whale.order.domain.payment.repository.PaymentHistoryRepository;
import com.whale.order.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    // 주문 취소 + Saga 보상 트랜잭션 (결제 동시 취소)
    @Transactional
    public void cancelOrder(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                log.warn("이미 취소된 주문 스킵 orderId={}", orderId);
                return;
            }
            order.cancel();
            orderRepository.save(order);
            historyRepository.save(OrderStatusHistory.builder()
                    .orders(order)
                    .status(OrderStatus.CANCELLED)
                    .changedBy(null)
                    .build());

            paymentRepository.findByOrders(order).ifPresent(payment -> {
                payment.cancel("재고 부족으로 인한 자동 환불");
                paymentRepository.save(payment);
                paymentHistoryRepository.save(PaymentHistory.builder()
                        .payment(payment)
                        .status(PaymentStatus.CANCELLED)
                        .reason("재고 부족으로 인한 자동 환불")
                        .build());
                log.info("Saga 보상 트랜잭션 — 결제 취소 paymentId={} orderId={}",
                        payment.getPaymentId(), orderId);
            });
        });
    }
}
