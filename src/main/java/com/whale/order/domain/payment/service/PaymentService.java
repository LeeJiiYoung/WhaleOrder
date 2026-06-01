package com.whale.order.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderStatusHistory;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.order.service.OrderKafkaProducer;
import com.whale.order.domain.payment.dto.PaymentInfoResponse;
import com.whale.order.domain.payment.dto.PaymentRequest;
import com.whale.order.domain.payment.dto.PaymentResponse;
import com.whale.order.domain.payment.entity.Payment;
import com.whale.order.domain.payment.entity.PaymentHistory;
import com.whale.order.domain.payment.entity.PaymentStatus;
import com.whale.order.domain.payment.repository.PaymentHistoryRepository;
import com.whale.order.domain.payment.repository.PaymentRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.exception.PaymentFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CartService cartService;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final java.util.Optional<OrderKafkaProducer> orderKafkaProducer;
    private final ObjectMapper objectMapper;

    /**
     * Mock 결제 처리.
     * 결제 성공(90%) → 주문 생성 → 대기열 등록
     * 결제 실패(10%) → 주문 취소 상태로 저장 → PaymentFailedException
     */
    @Transactional
    public PaymentResponse pay(Long memberId, PaymentRequest request) {
        CartResponse cart = cartService.getCart(memberId);
        if (cart.items().isEmpty()) {
            throw new IllegalStateException("장바구니가 비어있습니다");
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));

        // 주문 생성
        Orders order = Orders.builder()
                .member(member).store(store)
                .totalPrice(cart.totalPrice())
                .orderType(request.orderType())
                .customerRequest(request.customerRequest())
                .build();

        for (CartItem cartItem : cart.items()) {
            Menu menu = menuRepository.findById(cartItem.getMenuId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴: " + cartItem.getMenuId()));
            order.addOrderItem(OrderItem.builder()
                    .orders(order).menu(menu)
                    .quantity(cartItem.getQuantity())
                    .unitPrice(cartItem.getUnitPrice())
                    .options(toJson(cartItem.getSelectedOptions()))
                    .build());
        }
        orderRepository.save(order);

        // 결제 레코드 생성 (PENDING)
        Payment payment = Payment.builder()
                .orders(order).member(member)
                .amount(cart.totalPrice()).method(request.method())
                .build();
        paymentRepository.save(payment);
        savePaymentHistory(payment, PaymentStatus.PENDING, null);

        // Mock PG 결제 처리 (90% 성공)
        boolean success = ThreadLocalRandom.current().nextInt(100) < 90;

        if (success) {
            String txId = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            payment.success(txId);
            savePaymentHistory(payment, PaymentStatus.SUCCESS, null);

            orderHistoryRepository.save(OrderStatusHistory.builder()
                    .orders(order).status(OrderStatus.PENDING).changedBy(null).build());

            cartService.clearCart(memberId);
            orderKafkaProducer.ifPresent(p -> p.publish(order.getOrderId()));

            log.info("결제 성공 paymentId={} orderId={} txId={}", payment.getPaymentId(), order.getOrderId(), txId);
            return PaymentResponse.from(payment, order, 0);

        } else {
            // 결제 실패 → 주문 취소 (보상 트랜잭션)
            payment.fail("Mock PG 결제 오류");
            savePaymentHistory(payment, PaymentStatus.FAILED, "Mock PG 결제 오류");

            order.cancel();
            orderHistoryRepository.save(OrderStatusHistory.builder()
                    .orders(order).status(OrderStatus.CANCELLED).changedBy(null).build());

            log.warn("결제 실패 paymentId={} orderId={}", payment.getPaymentId(), order.getOrderId());
            throw new PaymentFailedException("결제에 실패했습니다. 다시 시도해주세요.");
        }
    }

    @Transactional(readOnly = true)
    public PaymentInfoResponse getPaymentByOrder(Long orderId, Long memberId) {
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다");
        }
        return paymentRepository.findByOrders(order)
                .map(PaymentInfoResponse::from)
                .orElse(null);
    }

    private void savePaymentHistory(Payment payment, PaymentStatus status, String reason) {
        paymentHistoryRepository.save(PaymentHistory.builder()
                .payment(payment).status(status).reason(reason).build());
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
