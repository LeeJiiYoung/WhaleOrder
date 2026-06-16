package com.whale.order.domain.order.event;

import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.order.service.OrderKafkaProducer;
import com.whale.order.domain.order.service.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final Optional<OrderKafkaProducer> orderKafkaProducer;
    private final OrderProcessingService orderProcessingService;
    private final CartService cartService;

    /**
     * DB 커밋 이후 실행 — Kafka 발행 실패가 주문 트랜잭션에 영향을 주지 않는다.
     * Kafka 발행 실패 시 주문은 PENDING 상태로 DB에 남으므로 모니터링 필요.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            orderKafkaProducer.ifPresentOrElse(
                    p -> p.publish(event.orderId()),
                    () -> orderProcessingService.process(event.orderId())
            );
            cartService.clearCart(event.memberId());
            log.info("[주문이벤트] 처리 완료 orderId={} memberId={}", event.orderId(), event.memberId());
        } catch (Exception e) {
            log.error("[주문이벤트] Kafka 발행 또는 장바구니 삭제 실패 orderId={} memberId={} error={}",
                    event.orderId(), event.memberId(), e.getMessage(), e);
        }
    }
}
