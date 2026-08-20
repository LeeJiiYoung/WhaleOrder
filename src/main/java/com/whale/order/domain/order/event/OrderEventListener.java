package com.whale.order.domain.order.event;

import com.whale.order.domain.cart.service.CartService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 주문 생성 후 부수작업 처리.
// Kafka 발행은 KafkaOutboxWorker 가 담당 (Outbox 패턴). 이 리스너는 장바구니 정리만.
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final CartService cartService;
    private final MeterRegistry meterRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            cartService.clearCart(event.memberId());
            log.info("[주문이벤트] 장바구니 정리 완료 orderId={} memberId={}",
                    event.orderId(), event.memberId());
        } catch (Exception e) {
            // 카트 정리 실패는 결제 자체에 영향 없음. 멱등성 키가 카트 내용을 포함하므로
            // 동일 카트 재결제는 캐시 반환되어 중복 결제 위험 낮음.
            log.error("[주문이벤트] 장바구니 삭제 실패 orderId={} memberId={} error={}",
                    event.orderId(), event.memberId(), e.getMessage(), e);
            meterRegistry.counter("cart.clear.failure").increment();
        }
    }
}