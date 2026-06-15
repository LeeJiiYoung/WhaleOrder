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
        if (order.getStatus() == OrderStatus.CANCELLED) return;

        Long storeId = order.getStore().getStoreId();
        List<OrderItem> deducted = new ArrayList<>();

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.deductStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                deducted.add(item);
            }
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
                try {
                    stockLockFacade.restoreStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                } catch (Exception restoreEx) {
                    Long menuId = item.getMenu().getMenuId();
                    int quantity = item.getQuantity();
                    log.error("재고 복구 실패 menuId={} quantity={}", menuId, quantity, restoreEx);
                    stockRestoreFailureRepository.save(StockRestoreFailure.builder()
                            .orderId(orderId)
                            .storeId(storeId)
                            .menuId(menuId)
                            .quantity(quantity)
                            .reason(restoreEx.getMessage())
                            .build());
                    orderSseService.broadcastStockRestoreFailure(orderId, menuId, quantity);
                }
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

    // DLQ Consumer에서 호출 — 3회 재시도 후에도 실패한 주문의 보상 트랜잭션 진입점
    public void compensate(Long orderId) {
        log.warn("DLQ 보상 트랜잭션 시작 orderId={}", orderId);
        orderCancelService.cancelOrder(orderId);
    }
}
