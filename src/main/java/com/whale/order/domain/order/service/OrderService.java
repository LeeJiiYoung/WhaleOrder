package com.whale.order.domain.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.dto.OrderCreateRequest;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.dto.QueuedOrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.stock.service.StockLockFacade;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.domain.order.event.OrderCreatedEvent;
import com.whale.order.global.exception.DuplicateRequestException;
import com.whale.order.global.idempotency.IdempotencyService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final StockLockFacade stockLockFacade;
    private final CartService cartService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderSseService orderSseService;
    private final MeterRegistry meterRegistry;

    private Counter orderCreatedCounter;

    @PostConstruct
    public void initMetrics() {
        orderCreatedCounter = Counter.builder("order.created.total")
                .description("누적 주문 생성 수")
                .register(meterRegistry);
    }

    // 장바구니 → 주문 생성 후 대기열 등록
    @Transactional
    public QueuedOrderResponse createOrder(Long memberId, OrderCreateRequest request) {
        CartResponse cart = cartService.getCart(memberId);
        if (cart.items().isEmpty()) {
            throw new IllegalStateException("장바구니가 비어 있습니다");
        }

        String key = generateIdempotencyKey(memberId, request, cart);

        QueuedOrderResponse cached = idempotencyService.getResult(key, QueuedOrderResponse.class);
        if (cached != null) {
            log.info("[주문생성] 멱등성 캐시 반환 memberId={} storeId={}", memberId, request.storeId());
            return cached;
        }

        if (!idempotencyService.markProcessing(key)) {
            QueuedOrderResponse completed = idempotencyService.getResult(key, QueuedOrderResponse.class);
            if (completed != null) return completed;
            log.warn("[주문생성] 중복 요청 감지 memberId={} storeId={}", memberId, request.storeId());
            throw new DuplicateRequestException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
            Store store = storeRepository.findById(request.storeId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));

            Orders order = Orders.builder()
                    .member(member)
                    .store(store)
                    .totalPrice(cart.totalPrice())
                    .orderType(request.orderType())
                    .customerRequest(request.customerRequest())
                    .build();

            for (CartItem cartItem : cart.items()) {
                Menu menu = menuRepository.findById(cartItem.getMenuId())
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴: " + cartItem.getMenuId()));

                // 재고 차감은 워커가 처리 — 여기서는 주문 항목만 저장
                OrderItem orderItem = OrderItem.builder()
                        .orders(order)
                        .menu(menu)
                        .quantity(cartItem.getQuantity())
                        .unitPrice(cartItem.getUnitPrice())
                        .options(toJson(cartItem.getSelectedOptions()))
                        .build();
                order.addOrderItem(orderItem);
            }

            orderRepository.save(order);
            historyRepository.save(OrderStatusHistory.builder()
                    .orders(order)
                    .status(OrderStatus.PENDING)
                    .changedBy(null)
                    .build());

            log.info("[주문생성] orderId={} memberId={} storeId={} totalPrice={} itemCount={}",
                    order.getOrderId(), memberId, request.storeId(),
                    cart.totalPrice(), cart.items().size());

            // DB 커밋 이후 Kafka 발행 및 장바구니 삭제 — OrderEventListener에서 처리
            eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), memberId));
            QueuedOrderResponse response = QueuedOrderResponse.of(order.getOrderId(), 0);

            orderCreatedCounter.increment();

            idempotencyService.saveResult(key, response);
            return response;

        } catch (Exception e) {
            log.error("[주문생성] 실패 memberId={} storeId={} error={}", memberId, request.storeId(), e.getMessage());
            idempotencyService.delete(key);
            throw e;
        } catch (Error e) {
            idempotencyService.delete(key);
            throw e;
        }
    }

    private String generateIdempotencyKey(Long memberId, OrderCreateRequest request, CartResponse cart) {
        String raw = memberId + ":"
                + request.storeId() + ":"
                + request.orderType() + ":"
                + Objects.toString(request.customerRequest(), "") + ":"
                + toJson(cart);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 알고리즘을 찾을 수 없습니다", e);
        }
    }

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
        // (기존 Redis 방식은 removeFromQueue() 성공 여부로 판단했음)
        if (order.isStockDeducted()) {
            Long storeId = order.getStore().getStoreId();
            for (OrderItem item : order.getOrderItems()) {
                stockLockFacade.restoreStock(storeId, item.getMenu().getMenuId(), item.getQuantity());
            }
        }

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
            default         -> "";
        };
        orderSseService.notifyStatusUpdate(orderId, order.getStatus().name(), message);

        // 모든 어드민에게 상태 변경 브로드캐스트
        OrderResponse response = OrderResponse.from(order);
        orderSseService.broadcastOrderStatusChange(response);

        return response;
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
