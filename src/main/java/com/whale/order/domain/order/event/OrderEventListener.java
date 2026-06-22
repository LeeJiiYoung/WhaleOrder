package com.whale.order.domain.order.event;

import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.order.service.OrderKafkaProducer;
import com.whale.order.domain.order.service.OrderProcessingService;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;

    /**
     * DB 커밋 이후 실행 — Kafka 발행 실패가 주문 트랜잭션에 영향을 주지 않는다.
     * Kafka 발행과 장바구니 삭제를 각각의 try-catch 로 분리해 한쪽 실패가 다른 쪽을 가리지 않게 한다.
     * Kafka 발행 실패 시 주문은 PENDING 상태로 DB에 남으므로 메트릭 알림으로 수동 재처리 필요.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        boolean publishOk = publishOrderEvent(event);
        boolean cartOk = clearCustomerCart(event);

        if (publishOk && cartOk) {
            log.info("[주문이벤트] 처리 완료 orderId={} memberId={}", event.orderId(), event.memberId());
        }
    }

    private boolean publishOrderEvent(OrderCreatedEvent event) {
        try {
            orderKafkaProducer.ifPresentOrElse(
                    p -> p.publish(event.orderId()),
                    () -> orderProcessingService.process(event.orderId())
            );
            return true;
        } catch (Exception e) {
            // 주문은 DB에 PENDING 상태로 남음 → 메트릭 알림 받고 수동 재처리해야 유령 주문 방지
            log.error("[주문이벤트] Kafka 발행 실패 (수동 재처리 필요) orderId={} error={}",
                    event.orderId(), e.getMessage(), e);
            meterRegistry.counter("order.event.publish.failure").increment();
            return false;
        }
    }

    private boolean clearCustomerCart(OrderCreatedEvent event) {
        try {
            cartService.clearCart(event.memberId());
            return true;
        } catch (Exception e) {
            // 멱등성 키가 카트 내용을 포함 → 동일 카트 재결제는 캐시 반환되므로 중복 결제 위험은 낮음
            // 다만 UX 상 결제 후에도 카트가 남는 문제는 메트릭으로 추적
            log.error("[주문이벤트] 장바구니 삭제 실패 orderId={} memberId={} error={}",
                    event.orderId(), event.memberId(), e.getMessage(), e);
            meterRegistry.counter("cart.clear.failure").increment();
            return false;
        }
    }
}
