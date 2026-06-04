package com.whale.order.domain.order.service;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.payment.entity.PaymentHistory;
import com.whale.order.domain.payment.entity.PaymentStatus;
import com.whale.order.domain.payment.repository.PaymentHistoryRepository;
import com.whale.order.domain.payment.repository.PaymentRepository;
import com.whale.order.domain.stock.entity.StockRestoreFailure;
import com.whale.order.domain.stock.repository.StockRestoreFailureRepository;
import com.whale.order.domain.stock.service.StockLockFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final StockRestoreFailureRepository stockRestoreFailureRepository;

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
        // 이미 취소된 주문은 스킵
        if (order.getStatus() == OrderStatus.CANCELLED) return;

        Long storeId = order.getStore().getStoreId();
        List<OrderItem> deducted = new ArrayList<>();

        try {
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.deductStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                deducted.add(item);
            }
            // 재고 차감 성공 → stockDeducted 플래그 설정 (취소 시 재고 복구 여부 판단에 사용)
            order.markStockDeducted();
            orderRepository.save(order);
            log.info("주문 처리 완료 orderId={}", orderId);
            orderSseService.notify(orderId, Map.of(
                    "status", "SUCCESS",
                    "orderId", orderId,
                    "message", "주문이 접수되었습니다. 매장에서 확인 중입니다."
            ));
            // 어드민 화면에 새 주문 실시간 브로드캐스트
            orderSseService.broadcastNewOrder(OrderResponse.from(order));

        } catch (Exception e) {
            // 재고 부족 → 이미 차감된 항목 복구 후 주문 취소
            for (OrderItem item : deducted) {
                try {
                    stockLockFacade.restoreStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                } catch (Exception restoreEx) {
                    Long menuId = item.getMenu().getMenuId();
                    int quantity = item.getQuantity();
                    log.error("재고 복구 실패 menuId={} quantity={}", menuId, quantity, restoreEx);
                    // DB에 기록 — 관리자가 나중에 수동 보정
                    stockRestoreFailureRepository.save(StockRestoreFailure.builder()
                            .orderId(orderId)
                            .storeId(storeId)
                            .menuId(menuId)
                            .quantity(quantity)
                            .reason(restoreEx.getMessage())
                            .build());
                    // 관리자 SSE 실시간 알림
                    orderSseService.broadcastStockRestoreFailure(orderId, menuId, quantity);
                }
            }
            cancelOrder(orderId);
            log.info("재고 부족으로 주문 취소 orderId={} reason={}", orderId, e.getMessage());
            orderSseService.notify(orderId, Map.of(
                    "status", "FAILED",
                    "orderId", orderId,
                    "message", e.getMessage()
            ));
        }
    }

    // DLQ Consumer에서 호출 — 3회 재시도 후에도 실패한 주문의 보상 트랜잭션 진입점
    @Transactional
    public void compensate(Long orderId) {
        log.warn("DLQ 보상 트랜잭션 시작 orderId={}", orderId);
        cancelOrder(orderId);
    }

    @Transactional
    protected void cancelOrder(Long orderId) {
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

            // Saga 보상 트랜잭션 — 재고 부족으로 주문 취소 시 결제도 함께 취소(환불)
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
