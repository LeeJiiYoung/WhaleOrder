package com.whale.order.domain.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.dto.OrderCreateRequest;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.exception.DuplicateRequestException;
import com.whale.order.global.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final StockRepository stockRepository;
    private final CartService cartService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    // 장바구니 → 주문 생성
    @Transactional
    public OrderResponse createOrder(Long memberId, OrderCreateRequest request) {
        CartResponse cart = cartService.getCart(memberId);
        if (cart.items().isEmpty()) {
            throw new IllegalStateException("장바구니가 비어 있습니다");
        }

        String key = generateIdempotencyKey(memberId, request, cart);

        // 완료된 결과가 있으면 캐시 반환
        OrderResponse cached = idempotencyService.getResult(key, OrderResponse.class);
        if (cached != null) return cached;

        // 처리 권한 획득 실패 → 경쟁 조건으로 방금 완료됐을 수 있으니 재확인
        if (!idempotencyService.markProcessing(key)) {
            OrderResponse completed = idempotencyService.getResult(key, OrderResponse.class);
            if (completed != null) return completed;
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

                // 재고 차감 (재고 레코드가 없으면 무제한으로 간주하여 skip)
                stockRepository.findWithLock(request.storeId(), cartItem.getMenuId())
                        .ifPresent(stock -> stock.deduct(cartItem.getQuantity()));

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

            cartService.clearCart(memberId);

            OrderResponse response = orderRepository.findByIdWithDetails(order.getOrderId())
                    .map(OrderResponse::from)
                    .orElseThrow();

            idempotencyService.saveResult(key, response);
            return response;

        } catch (Exception e) {
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

        // 재고 복구
        Long storeId = order.getStore().getStoreId();
        for (OrderItem item : order.getOrderItems()) {
            stockRepository.findWithLock(storeId, item.getMenu().getMenuId())
                    .ifPresent(stock -> stock.restore(item.getQuantity()));
        }

        Member member = memberRepository.findById(memberId).orElseThrow();
        historyRepository.save(OrderStatusHistory.builder()
                .orders(order)
                .status(OrderStatus.CANCELLED)
                .changedBy(member)
                .build());

        return OrderResponse.from(order);
    }

    // 전체 주문 목록 (어드민)
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders(OrderStatus status) {
        List<Orders> orders = (status != null)
                ? orderRepository.findByStatusWithDetails(status)
                : orderRepository.findAllWithDetails();
        return orders.stream().map(OrderResponse::from).toList();
    }

    // 주문 상태 변경 (어드민)
    @Transactional
    public OrderResponse changeStatus(Long orderId, String action, Long adminMemberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        Member admin = memberRepository.findById(adminMemberId).orElseThrow();

        switch (action) {
            case "accept"   -> order.accept();
            case "prepare"  -> order.startPreparing();
            case "complete" -> order.complete();
            default -> throw new IllegalArgumentException("알 수 없는 액션: " + action);
        }

        historyRepository.save(OrderStatusHistory.builder()
                .orders(order)
                .status(order.getStatus())
                .changedBy(admin)
                .build());

        return OrderResponse.from(order);
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
