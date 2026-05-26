package com.whale.order.domain.order.service;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
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

    private final OrderQueueService orderQueueService;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final StockLockFacade stockLockFacade;
    private final OrderSseService orderSseService;

    // 대기열에서 꺼내서 재고 차감 처리
    public void processNext() {
        Long orderId = orderQueueService.dequeue();
        if (orderId == null) return;

        Orders order = orderRepository.findByIdWithDetails(orderId).orElse(null);
        if (order == null) {
            log.warn("대기열에서 꺼냈으나 주문을 찾을 수 없음 orderId={}", orderId);
            return;
        }

        // 이미 취소된 주문은 스킵
        if (order.getStatus() == OrderStatus.CANCELLED) return;

        Long storeId = order.getStore().getStoreId();
        Long memberId = order.getMember().getMemberId();
        List<OrderItem> deducted = new ArrayList<>();

        try {
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.deductStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                deducted.add(item);
            }
            // 재고 차감 성공 → 주문 유지 (매장 접수 대기 PENDING)
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
                    log.error("재고 복구 실패 menuId={}", item.getMenu().getMenuId(), restoreEx);
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

    @Transactional
    protected void cancelOrder(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.cancel();
            historyRepository.save(OrderStatusHistory.builder()
                    .orders(order)
                    .status(OrderStatus.CANCELLED)
                    .changedBy(null)
                    .build());
        });
    }
}
