package com.whale.order.domain.order.service;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.stock.entity.StockRestoreFailure;
import com.whale.order.domain.stock.repository.StockRestoreFailureRepository;
import com.whale.order.domain.stock.service.StockLockFacade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final StockLockFacade stockLockFacade;
    private final OrderSseService orderSseService;
    private final StockRestoreFailureRepository stockRestoreFailureRepository;
    private final OrderCancelService orderCancelService;
    private final MeterRegistry meterRegistry;

    private Counter processedSuccessCounter;
    private Counter processedFailureCounter;
    private Counter stockShortageCounter;

    @PostConstruct
    public void initMetrics() {
        processedSuccessCounter = Counter.builder("order.processed")
                .tag("result", "success")
                .description("주문 처리 성공 횟수")
                .register(meterRegistry);
        processedFailureCounter = Counter.builder("order.processed")
                .tag("result", "failure")
                .description("주문 처리 실패 횟수")
                .register(meterRegistry);
        stockShortageCounter = Counter.builder("order.stock.shortage")
                .description("재고 부족 발생 횟수")
                .register(meterRegistry);
    }

    // Kafka Consumer에서 직접 orderId를 받아서 처리 (Redis 폴링 불필요)
    public void process(Long orderId) {
        Orders order = orderRepository.findByIdWithDetails(orderId).orElse(null);
        if (order == null) {
            log.warn("주문을 찾을 수 없음 orderId={}", orderId);
            return;
        }
        processOrder(orderId, order);
    }

    private void processOrder(Long orderId, Orders order) {
        // Kafka at-least-once 재전달 방어 — 이미 차감된 주문은 무시
        if (order.isStockDeducted() || order.getStatus() == OrderStatus.CANCELLED) return;

        Long storeId = order.getStore().getStoreId();
        List<OrderItem> deducted = new ArrayList<>();

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.deductStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                deducted.add(item);
            }
            // 재고차감: O
            order.markStockDeducted();
            orderRepository.save(order);

            sample.stop(Timer.builder("order.processing.time")
                    .tag("result", "success")
                    .description("주문 처리 소요 시간")
                    .register(meterRegistry));
            processedSuccessCounter.increment();

            log.info("주문 처리 완료 orderId={}", orderId);
            orderSseService.notify(orderId, Map.of(
                    "status", "SUCCESS",
                    "orderId", orderId,
                    "message", "주문이 접수되었습니다. 매장에서 확인 중입니다."
            ));
            orderSseService.broadcastNewOrder(OrderResponse.from(order));

        } catch (Exception e) {
            sample.stop(Timer.builder("order.processing.time")
                    .tag("result", "failure")
                    .description("주문 처리 소요 시간")
                    .register(meterRegistry));
            processedFailureCounter.increment();
            stockShortageCounter.increment();

            for (OrderItem item : deducted) {
                restoreWithRetry(orderId, storeId, item);
            }
            orderCancelService.cancelOrder(orderId);
            log.info("재고 부족으로 주문 취소 orderId={} reason={}", orderId, e.getMessage());
            orderSseService.notify(orderId, Map.of(
                    "status", "FAILED",
                    "orderId", orderId,
                    "message", e.getMessage()
            ));
        }
    }

    private static final int RESTORE_MAX_ATTEMPTS = 3;
    private static final long RESTORE_RETRY_DELAY_MS = 200;

    private void restoreWithRetry(Long orderId, Long storeId, OrderItem item) {
        Long menuId = item.getMenu().getMenuId();
        int quantity = item.getQuantity();
        Exception lastEx = null;

        for (int attempt = 1; attempt <= RESTORE_MAX_ATTEMPTS; attempt++) {
            try {
                stockLockFacade.restoreStock(storeId, menuId, quantity);
                log.info("재고 복구 성공 orderId={} menuId={} attempt={}", orderId, menuId, attempt);
                return;
            } catch (Exception e) {
                lastEx = e;
                log.warn("재고 복구 시도 실패 orderId={} menuId={} attempt={}/{} error={}",
                        orderId, menuId, attempt, RESTORE_MAX_ATTEMPTS, e.getMessage());
                if (attempt < RESTORE_MAX_ATTEMPTS) {
                    try { Thread.sleep(RESTORE_RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 재시도 모두 소진 — 수동 처리 대상으로 기록
        log.error("재고 복구 최종 실패 orderId={} menuId={} quantity={}", orderId, menuId, quantity, lastEx);
        stockRestoreFailureRepository.save(StockRestoreFailure.builder()
                .orderId(orderId)
                .storeId(storeId)
                .menuId(menuId)
                .quantity(quantity)
                .reason(lastEx != null ? lastEx.getMessage() : "unknown")
                .build());
        orderSseService.broadcastStockRestoreFailure(orderId, menuId, quantity);
    }

    // DLQ Consumer에서 호출 — 3회 재시도 후에도 실패한 주문의 보상 트랜잭션 진입점
    public void compensate(Long orderId) {
        log.warn("DLQ 보상 트랜잭션 시작 orderId={}", orderId);
        orderCancelService.cancelOrder(orderId);
    }
}
