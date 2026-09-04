package com.whale.order.domain.payment.service;

import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 대기(AWAITING_PAYMENT) 상태로 오래 방치된 주문을 주기적으로 정리한다.
 *
 * <p>토스 결제창을 열어놓고 탭을 닫거나 브라우저를 꺼버리는 등, 클라이언트가 성공(confirm)·실패
 * (PaymentController.cancelPending) 어느 콜백도 서버에 보내지 못하는 경우 prepare()가 만든 임시
 * 주문이 AWAITING_PAYMENT로 영영 남을 수 있다. 클라이언트 콜백이 처리하지 못하는 이 마지막 빈틈을
 * 서버가 스스로 메운다. 자세한 배경은 {@link PaymentService#cancelAwaitingPayment} 참고.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSweepScheduler {

    // 이 시간 이상 AWAITING_PAYMENT로 남아있으면 방치된 것으로 본다.
    // 토스 결제창에서 사용자가 카드 정보를 입력하는 데 걸리는 시간을 넉넉히 감안한 값.
    private static final long STALE_MINUTES = 30;

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 5 * 60 * 1000) // 5분마다
    public void sweepStaleAwaitingPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<Orders> stale = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.AWAITING_PAYMENT, cutoff);
        if (stale.isEmpty()) {
            return;
        }

        log.info("[결제대기정리:스케줄러] {}건 발견 (createdAt < {})", stale.size(), cutoff);
        for (Orders order : stale) {
            try {
                // 주문 1건마다 별도 트랜잭션(PaymentService의 @Transactional, 프록시 경유 호출) —
                // 한 건이 실패해도 나머지 정리는 계속 진행된다.
                paymentService.cancelAwaitingPaymentSystem(order.getOrderId(), "결제 대기 시간 초과 자동 정리");
            } catch (Exception e) {
                log.error("[결제대기정리:스케줄러] 실패 orderId={}", order.getOrderId(), e);
            }
        }
    }
}
