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
import com.whale.order.domain.order.event.OrderCreatedEvent;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.payment.dto.PaymentInfoResponse;
import com.whale.order.domain.payment.dto.PaymentRequest;
import com.whale.order.domain.payment.dto.PaymentResponse;
import com.whale.order.domain.payment.entity.Payment;
import com.whale.order.domain.payment.entity.PaymentHistory;
import com.whale.order.domain.payment.entity.PaymentStatus;
import com.whale.order.domain.payment.repository.PaymentHistoryRepository;
import com.whale.order.domain.payment.repository.PaymentRepository;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.entity.StoreStatus;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.exception.DuplicateRequestException;
import com.whale.order.global.exception.PaymentFailedException;
import com.whale.order.global.idempotency.IdempotencyService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final StockRepository stockRepository;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Mock 결제 처리.
     * 결제 성공(90%) → 주문 생성 → 대기열 등록
     * 결제 실패(10%) → 주문 취소 상태로 저장 → PaymentFailedException
     *
     * noRollbackFor = PaymentFailedException — 결제 실패 시도 이력(PaymentHistory.FAILED)과
     * 주문/결제 종결 상태(Orders.CANCELLED / Payment.FAILED)를 DB에 보존하기 위해
     * 의도적으로 throw 해도 트랜잭션을 commit 시킨다. 그 외 RuntimeException(예: DuplicateRequest,
     * IllegalState 등)은 기본 정책대로 롤백.
     */
    @Transactional(noRollbackFor = PaymentFailedException.class)
    public PaymentResponse pay(Long memberId, PaymentRequest request) {
        CartResponse cart = cartService.getCart(memberId);
        if (cart.items().isEmpty()) {
            // 두 가지 케이스를 모두 포괄:
            //  1) 사용자가 진짜 빈 카트로 결제 버튼을 누른 경우
            //  2) 동시 결제 중 다른 브라우저/탭에서 먼저 결제 성공 → AFTER_COMMIT clearCart 로 카트 비워진 경우
            throw new IllegalStateException(
                    "장바구니가 비어있습니다. 이미 주문이 처리되었을 수 있으니 주문 내역을 확인해주세요.");
        }

        // 표시 금액 확인 — 클라가 본 화면 금액과 서버 재계산 금액 불일치 시 차단.
        // 메뉴 가격 변동·다른 탭 카트 수정·중간자 변조 모두 여기서 거름. 실제 청구는 서버 계산값만 사용.
        // expectedAmount 가 null 이면 (구버전 프런트) 검증을 건너뛰고 통과 — 프런트 배포 후 NotNull 격상.
        if (request.expectedAmount() != null
                && !Objects.equals(cart.totalPrice(), request.expectedAmount())) {
            log.warn("[결제] 표시 금액 불일치 memberId={} expected={} actual={}",
                    memberId, request.expectedAmount(), cart.totalPrice());
            throw new IllegalStateException(
                    "표시된 금액과 실제 금액이 다릅니다. 장바구니를 새로고침해주세요.");
        }

        // 같은 장바구니·매장·결제 수단으로 들어온 중복 요청을 1회만 처리
        String key = generateIdempotencyKey(memberId, request, cart);

        PaymentResponse cached = idempotencyService.getResult(key, PaymentResponse.class);
        if (cached != null) {
            log.info("[결제] 멱등성 캐시 반환 memberId={} storeId={}", memberId, request.storeId());
            return cached;
        }

        if (!idempotencyService.markProcessing(key)) {
            PaymentResponse completed = idempotencyService.getResult(key, PaymentResponse.class);
            if (completed != null) return completed;
            log.warn("[결제] 중복 요청 감지 memberId={} storeId={}", memberId, request.storeId());
            throw new DuplicateRequestException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
            Store store = storeRepository.findById(request.storeId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));

            // 영업 중인 매장만 주문 접수
            if (store.getStatus() != StoreStatus.OPEN) {
                throw new IllegalStateException("현재 영업 중이지 않은 매장입니다");
            }

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

                // 판매 중지/판매 기간 외 메뉴 차단
                if (!menu.isOnSale()) {
                    throw new IllegalStateException("판매 중지된 메뉴입니다: " + menu.getName());
                }

                // 선택 매장에서 판매하는 메뉴인지 검증 (Stock 레코드 존재 여부)
                // → 다른 매장 메뉴 끼워 넣기·재고 미설정 메뉴 결제 방지
                if (stockRepository.findByStoreAndMenu(store.getStoreId(), menu.getMenuId()).isEmpty()) {
                    throw new IllegalStateException("해당 매장에서 판매하지 않는 메뉴입니다: " + menu.getName());
                }

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

                // DB 커밋 이후 Kafka 발행 및 장바구니 삭제 — OrderEventListener(AFTER_COMMIT)에서 처리.
                // 커밋 실패 시 메시지 미발행 + 장바구니 보존 → 일관성 확보.
                eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), memberId));

                Counter.builder("payment.processed")
                        .tag("result", "success")
                        .description("결제 처리 횟수")
                        .register(meterRegistry).increment();
                log.info("결제 성공 paymentId={} orderId={} txId={}", payment.getPaymentId(), order.getOrderId(), txId);

                PaymentResponse response = PaymentResponse.from(payment, order, 0);
                idempotencyService.saveResult(key, response);
                return response;

            } else {
                // 결제 실패 → 주문 취소 (보상 트랜잭션).
                // outer @Transactional(noRollbackFor = PaymentFailedException) 덕분에
                // 아래 변경(Payment.FAILED, PaymentHistory.FAILED, Orders.CANCELLED, History.CANCELLED)이
                // throw 후에도 그대로 DB에 commit 된다 → 시도 이력 보존.
                payment.fail("Mock PG 결제 오류");
                savePaymentHistory(payment, PaymentStatus.FAILED, "Mock PG 결제 오류");

                order.cancel();
                orderHistoryRepository.save(OrderStatusHistory.builder()
                        .orders(order).status(OrderStatus.CANCELLED).changedBy(null).build());

                Counter.builder("payment.processed")
                        .tag("result", "failure")
                        .description("결제 처리 횟수")
                        .register(meterRegistry).increment();
                log.warn("결제 실패 paymentId={} orderId={}", payment.getPaymentId(), order.getOrderId());
                throw new PaymentFailedException("결제에 실패했습니다. 다시 시도해주세요.");
            }
        } catch (Exception e) {
            // 실패 시 키 삭제 → 클라이언트 재시도 허용 (REQUIRES_NEW 라 outer 롤백과 무관)
            idempotencyService.delete(key);
            throw e;
        } catch (Error e) {
            idempotencyService.delete(key);
            throw e;
        }
    }

    private String generateIdempotencyKey(Long memberId, PaymentRequest request, CartResponse cart) {
        String raw = memberId + ":"
                + request.storeId() + ":"
                + request.method() + ":"
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

    /**
     * 주문 취소 시 결제 환불 처리.
     * SUCCESS 상태 결제만 CANCELLED 로 전이하고 PaymentHistory 기록.
     * 그 외 상태(PENDING/FAILED/CANCELLED)는 무시 — 환불 의미 없거나 이미 처리됨.
     */
    @Transactional
    public void cancelPayment(Orders order, String reason) {
        paymentRepository.findByOrders(order)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .ifPresent(payment -> {
                    payment.cancel(reason);
                    paymentRepository.save(payment);
                    paymentHistoryRepository.save(PaymentHistory.builder()
                            .payment(payment)
                            .status(PaymentStatus.CANCELLED)
                            .reason(reason)
                            .build());
                    log.info("[결제환불] paymentId={} orderId={} reason={}",
                            payment.getPaymentId(), order.getOrderId(), reason);
                });
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
