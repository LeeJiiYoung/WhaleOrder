package com.whale.order.domain.order.service;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.payment.service.PaymentService;
import com.whale.order.domain.stock.service.StockLockFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final StockLockFacade stockLockFacade;
    private final OrderSseService orderSseService;
    private final PaymentService paymentService;

    // SSE 구독 전 주문 소유권 검증 후 반환 (OrderQueueController 전용)
    @Transactional(readOnly = true)
    public Orders findOrderForSse(Long orderId, Long memberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다");
        }
        return order;
    }

    // 내 주문 목록
    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders(Long memberId) {
        return orderRepository.findByMemberIdWithStore(memberId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    // 주문 상세
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long memberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다");
        }
        return OrderResponse.from(order);
    }

    // 주문 취소 (고객)
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long memberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 취소할 수 있습니다");
        }
        order.cancel();
        log.info("[주문취소] orderId={} memberId={} stockDeducted={}", orderId, memberId, order.isStockDeducted());

        // Kafka 방식: stockDeducted 플래그로 재고 차감 완료 여부 판단
        if (order.isStockDeducted()) {
            Long storeId = order.getStore().getStoreId();
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.restoreStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
            }
        }

        // 선결제 흐름: PENDING 주문이라도 결제는 이미 SUCCESS 상태 → 환불 처리
        paymentService.cancelPayment(order, "고객 주문 취소");

        Member member = memberRepository.findById(memberId).orElseThrow();
        historyRepository.save(OrderStatusHistory.builder()
                .orders(order)
                .status(OrderStatus.CANCELLED)
                .changedBy(member)
                .build());

        return OrderResponse.from(order);
    }

    // 전체 주문 목록 (어드민) — OWNER는 본인 매장 주문만 조회
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders(List<OrderStatus> statuses, Long callerId) {
        Member caller = memberRepository.findById(callerId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));

        List<Orders> orders;
        if (statuses == null || statuses.isEmpty()) {
            orders = orderRepository.findAllWithDetails();
        } else if (statuses.size() == 1) {
            orders = orderRepository.findByStatusWithDetails(statuses.get(0));
        } else {
            orders = orderRepository.findByStatusesWithDetails(statuses);
        }

        if (caller.getRole() == MemberRole.OWNER) {
            orders = orders.stream()
                    .filter(order -> order.getStore().getOwner().getMemberId().equals(callerId))
                    .toList();
        }

        return orders.stream().map(OrderResponse::from).toList();
    }

    // 주문 상태 변경 (어드민) — OWNER는 본인 매장 주문만 처리 가능
    @Transactional
    public OrderResponse changeStatus(Long orderId, String action, Long adminMemberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        Member admin = memberRepository.findById(adminMemberId).orElseThrow();

        if (admin.getRole() == MemberRole.OWNER
                && !order.getStore().getOwner().getMemberId().equals(adminMemberId)) {
            throw new IllegalArgumentException("본인 매장의 주문만 처리할 수 있습니다");
        }

        OrderStatus before = order.getStatus();
        switch (action) {
            case "prepare"  -> order.startPreparing();
            case "complete" -> order.complete();
            case "cancel"   -> {
                order.cancelByAdmin();
                // 재고 차감이 이미 완료된 주문(PREPARING 등)은 복구
                if (order.isStockDeducted()) {
                    Long storeId = order.getStore().getStoreId();
                    for (OrderItem item : order.getOrderItems()) {
                        stockLockFacade.restoreStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
                    }
                }
                // 결제 환불
                paymentService.cancelPayment(order, "관리자 주문 취소");
            }
            default -> throw new IllegalArgumentException("알 수 없는 액션: " + action);
        }
        log.info("[상태변경] orderId={} {} → {} adminId={}", orderId, before, order.getStatus(), adminMemberId);

        historyRepository.save(OrderStatusHistory.builder()
                .orders(order)
                .status(order.getStatus())
                .changedBy(admin)
                .build());

        // 고객에게 실시간 상태 알림
        String message = switch (action) {
            case "prepare"  -> "음료를 준비 중입니다";
            case "complete" -> "주문이 완료되었습니다. 찾아가주세요 ☕";
            case "cancel"   -> "매장 사정으로 주문이 취소되었습니다. 결제는 환불 처리됩니다.";
            default         -> "";
        };
        orderSseService.notifyStatusUpdate(orderId, order.getStatus().name(), message);

        // 모든 어드민에게 상태 변경 브로드캐스트
        OrderResponse response = OrderResponse.from(order);
        orderSseService.broadcastOrderStatusChange(response);

        return response;
    }
}
