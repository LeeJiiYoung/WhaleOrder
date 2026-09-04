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
import com.whale.order.domain.payment.client.TossCancelResponse;
import com.whale.order.domain.payment.client.TossConfirmResponse;
import com.whale.order.domain.payment.client.TossPaymentClient;
import com.whale.order.domain.payment.dto.*;
import com.whale.order.domain.payment.entity.Payment;
import com.whale.order.domain.payment.entity.PaymentHistory;
import com.whale.order.domain.payment.entity.PaymentMethod;
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
import com.whale.order.global.outbox.KafkaOutboxService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

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
    private final KafkaOutboxService kafkaOutboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TossPaymentClient tossPaymentClient;

    // 토스 orderId는 6~64자 영문/숫자/-/_ 만 허용 — 우리 내부 PK(Long, 1~2자리도 흔함)를
    // 그대로 못 쓰므로 접두사를 붙여 토스 전용 문자열을 따로 만든다. DB엔 여전히 내부 PK만 저장.
    private static final String TOSS_ORDER_ID_PREFIX = "whale-";

    // confirm 멱등성 키 접두사 — prepare()는 "같은 내용의 요청"을 해시로 구분하지만,
    // confirm은 그럴 필요 없이 "같은 주문의 승인 요청"만 한 번으로 묶으면 되므로 orderId를 그대로 쓴다.
    private static final String CONFIRM_IDEMPOTENCY_PREFIX = "confirm:";

    /**
     * 주문 취소 시 결제 환불 처리.
     * SUCCESS 상태 결제만 CANCELLED 로 전이하고 PaymentHistory 기록.
     * 그 외 상태(PENDING/FAILED/CANCELLED)는 무시 — 환불 의미 없거나 이미 처리됨.
     *
     * <p>토스 취소(환불) API를 먼저 호출해 실제로 승인받은 뒤에만 우리 DB도 CANCELLED로 바꾼다.
     * 토스가 거절하면 예외를 그대로 던져 호출부(OrderService.cancelOrder 등)의 트랜잭션을
     * 롤백시킨다 — noRollbackFor 를 안 쓰는 이유도 그 때문이다. confirm()과 달리 여기서는
     * "환불 실패 이력을 남기고 주문은 취소된 채로 두는" 게 아니라, 돈이 안 돌아왔으면 주문 취소
     * 자체도 없었던 일로 되돌리는 게 맞다고 판단했다. (재고 복구·주문 상태 변경도 같이 롤백됨)</p>
     */
    @Transactional
    public void cancelPayment(Orders order, String reason) {
        paymentRepository.findByOrders(order)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .ifPresent(payment -> {
                    // 과거 Mock PG 시절 테스트 데이터(externalTxId가 "MOCK-..." 형태)는 토스에 실재하는
                    // paymentKey가 아니다 — 그대로 토스에 취소를 요청하면 무조건 거절당하므로 건너뛴다.
                    // 지금은 결제 흐름이 전부 confirm()(토스) 이라 새로 발생하지는 않지만, 레거시 데이터
                    // 방어용으로 가드는 남겨둔다.
                    String tossStatus = "SKIPPED(MOCK)";
                    if (payment.getExternalTxId() != null && !payment.getExternalTxId().startsWith("MOCK-")) {
                        try {
                            TossCancelResponse tossResponse =
                                    tossPaymentClient.cancel(payment.getExternalTxId(), reason);
                            tossStatus = tossResponse.status();
                        } catch (RestClientResponseException e) {
                            log.error("[결제환불] 토스 취소 거절 paymentId={} orderId={} status={} body={}",
                                    payment.getPaymentId(), order.getOrderId(), e.getStatusCode(), e.getResponseBodyAsString());
                            throw new PaymentFailedException("결제 취소(환불)에 실패했습니다. 잠시 후 다시 시도해주세요.");
                        }
                    }

                    payment.cancel(reason);
                    paymentRepository.save(payment);
                    paymentHistoryRepository.save(PaymentHistory.builder()
                            .payment(payment)
                            .status(PaymentStatus.CANCELLED)
                            .reason(reason)
                            .build());
                    log.info("[결제환불] 완료 paymentId={} orderId={} reason={} tossStatus={}",
                            payment.getPaymentId(), order.getOrderId(), reason, tossStatus);
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

    /**
     * 결제 전
     * @param memberId
     * @param request
     * @return
     */
    @Transactional
    public PaymentPrepareResponse prepare(Long memberId, PaymentPrepareRequest request) {
        CartResponse cart = cartService.getCart(memberId);
        if (cart.items().isEmpty()) {
            throw new IllegalStateException("장바구니가 비어있습니다.");
        }
        if (!Objects.equals(cart.totalPrice(), request.expectedAmount())) {
            throw new IllegalStateException("표시된 금액과 실제 금액이 다릅니다. 장바구니를 새로고침해주세요.");
        }

        String key = generatePrepareIdempotencyKey(memberId, request, cart);

        PaymentPrepareResponse cached = idempotencyService.getResult(key, PaymentPrepareResponse.class);
        if (cached != null) {
            log.info("[결제준비] 멱등성 캐시 반환 memberId={} storeId={}", memberId, request.storeId());
            return cached;
        }
        if (!idempotencyService.markProcessing(key)) {
            PaymentPrepareResponse completed = idempotencyService.getResult(key, PaymentPrepareResponse.class);
            if (completed != null) return completed;
            throw new DuplicateRequestException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
            Store store = storeRepository.findById(request.storeId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));
            if (store.getStatus() != StoreStatus.OPEN) {
                throw new IllegalStateException("현재 영업 중이지 않은 매장입니다");
            }

            Orders order = Orders.builder()
                    .member(member).store(store)
                    .totalPrice(cart.totalPrice())
                    .orderType(request.orderType())
                    .customerRequest(request.customerRequest())
                    .build();

            for (CartItem cartItem : cart.items()) {
                Menu menu = menuRepository.findById(cartItem.getMenuId())
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴: " + cartItem.getMenuId()));
                if (!menu.isOnSale()) {
                    throw new IllegalStateException("판매 중지된 메뉴입니다: " + menu.getName());
                }
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

            Payment payment = Payment.builder()
                    .orders(order).member(member)
                    .amount(cart.totalPrice())
                    .build();   // method 없이 저장 — confirm에서 토스 응답으로 채움
            paymentRepository.save(payment);
            savePaymentHistory(payment, PaymentStatus.PENDING, null);

            PaymentPrepareResponse response =
                    new PaymentPrepareResponse(order.getOrderId(), TOSS_ORDER_ID_PREFIX + order.getOrderId(),
                            payment.getAmount(), buildOrderName(order));
            idempotencyService.saveResult(key, response);
            return response;

        } catch (Exception | Error e) {
            idempotencyService.delete(key);
            throw e;
        }
    }

    /**
     * 결제 전 멱등키 생성
     * @param memberId
     * @param request
     * @param cart
     * @return
     */
    private String generatePrepareIdempotencyKey(Long memberId, PaymentPrepareRequest request, CartResponse cart) {
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

    private String buildOrderName(Orders order) {
        List<OrderItem> items = order.getOrderItems();
        if (items.isEmpty()) {
            return "주문";
        }
        String firstMenuName = items.get(0).getMenu().getName();
        return items.size() > 1
                ? firstMenuName + " 외 " + (items.size() - 1) + "건"
                : firstMenuName;
    }

    /**
     * 토스 전용 주문번호("whale-17")를 내부 PK(Long 17)로 되돌린다.
     * prepare에서 우리가 직접 만든 형식이므로, 접두사가 없거나 뒷부분이 숫자가 아니면
     * 위조/오염된 값으로 보고 거부한다.
     */
    private Long parseTossOrderId(String tossOrderId) {
        if (tossOrderId == null || !tossOrderId.startsWith(TOSS_ORDER_ID_PREFIX)) {
            throw new IllegalArgumentException("잘못된 주문번호입니다");
        }
        try {
            return Long.parseLong(tossOrderId.substring(TOSS_ORDER_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 주문번호입니다");
        }
    }

    /**
     * 토스 결제 승인.
     *
     * <p>successUrl 리다이렉트로 받은 paymentKey·orderId·amount를 그대로 넘겨받는다.
     * prepare 때 저장해둔 금액과 대조 검증한 뒤 토스 승인 API를 호출하고,
     * 성공하면 pay()의 성공 분기와 동일하게 Kafka outbox·OrderCreatedEvent를 발행한다.</p>
     *
     * noRollbackFor = PaymentFailedException — Payment.FAILED / Orders.CANCELLED 전이를
     * DB에 보존하기 위해 pay()와 동일한 정책을 쓴다.
     */
    @Transactional(noRollbackFor = PaymentFailedException.class)
    public PaymentResponse confirm(Long memberId, PaymentConfirmRequest request) {
        Long orderId = parseTossOrderId(request.orderId());

        // 같은 주문에 대한 confirm 동시 호출을 한 번만 처리 — StrictMode 이중 호출, 네트워크 재시도,
        // 사용자의 새로고침/뒤로가기 등으로 두 요청이 거의 동시에 들어와도 하나만 실제로 토스를 호출한다.
        String key = CONFIRM_IDEMPOTENCY_PREFIX + orderId;

        PaymentResponse cached = idempotencyService.getResult(key, PaymentResponse.class);
        if (cached != null) {
            log.info("[결제승인] 멱등성 캐시 반환 orderId={}", orderId);
            return cached;
        }
        if (!idempotencyService.markProcessing(key)) {
            PaymentResponse completed = idempotencyService.getResult(key, PaymentResponse.class);
            if (completed != null) return completed;
            log.warn("[결제승인] 중복 요청 감지 orderId={}", orderId);
            throw new DuplicateRequestException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            Orders order = orderRepository.findByIdWithDetails(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
            if (!order.getMember().getMemberId().equals(memberId)) {
                throw new IllegalArgumentException("본인 주문만 결제할 수 있습니다");
            }
            Payment payment = paymentRepository.findByOrders(order)
                    .orElseThrow(() -> new IllegalArgumentException("결제 정보를 찾을 수 없습니다"));

            // 멱등성 키가 만료(60초)된 뒤 confirm이 다시 호출된 경우 — 토스에 재승인 요청을 보내지 않고 그대로 반환
            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                PaymentResponse response = PaymentResponse.from(payment, order, 0);
                idempotencyService.saveResult(key, response);
                return response;
            }
            if (payment.getStatus() != PaymentStatus.PENDING) {
                throw new IllegalStateException("승인할 수 없는 결제 상태입니다: " + payment.getStatus());
            }

            // prepare 때 저장해둔 금액과 대조 — successUrl 리다이렉트 과정에서 금액이 조작되지 않았는지 확인
            if (!Objects.equals(payment.getAmount(), request.amount())) {
                log.warn("[결제승인] 금액 불일치 orderId={} expected={} actual={}",
                        order.getOrderId(), payment.getAmount(), request.amount());
                throw new IllegalStateException("결제 금액이 일치하지 않습니다");
            }

            TossConfirmResponse tossResponse;
            try {
                tossResponse = tossPaymentClient.confirm(request.paymentKey(), request.orderId(), request.amount());
            } catch (RestClientResponseException e) {
                // 토스가 승인을 거절(4xx/5xx) — Payment.FAILED 전환 + 주문 취소 (Saga 보상)
                payment.fail("토스 승인 거절: " + e.getStatusCode());
                savePaymentHistory(payment, PaymentStatus.FAILED, e.getResponseBodyAsString());

                order.cancel();
                orderHistoryRepository.save(OrderStatusHistory.builder()
                        .orders(order).status(OrderStatus.CANCELLED).changedBy(null).build());

                Counter.builder("payment.processed")
                        .tag("result", "failure")
                        .description("결제 처리 횟수")
                        .register(meterRegistry).increment();
                log.warn("[결제승인] 토스 거절 orderId={} status={} body={}",
                        order.getOrderId(), e.getStatusCode(), e.getResponseBodyAsString());
                throw new PaymentFailedException("결제 승인에 실패했습니다.");
            }

            PaymentMethod method = resolveMethod(tossResponse.method(),
                    tossResponse.easyPay() != null ? tossResponse.easyPay().provider() : null);
            payment.success(tossResponse.paymentKey(), method);
            savePaymentHistory(payment, PaymentStatus.SUCCESS, null);

            orderHistoryRepository.save(OrderStatusHistory.builder()
                    .orders(order).status(OrderStatus.PENDING).changedBy(null).build());

            // Outbox 도입: 커밋과 동일 트랜잭션에서 발행 예약 → 원자성 확보.
            String outboxPayload = "{\"orderId\":" + order.getOrderId() + "}";
            kafkaOutboxService.enqueue("order-created", order.getOrderId(), outboxPayload);
            eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), memberId));

            Counter.builder("payment.processed")
                    .tag("result", "success")
                    .description("결제 처리 횟수")
                    .register(meterRegistry).increment();
            log.info("[결제승인] 완료 paymentId={} orderId={} paymentKey={} method={}",
                    payment.getPaymentId(), order.getOrderId(), tossResponse.paymentKey(), method);

            PaymentResponse response = PaymentResponse.from(payment, order, 0);
            idempotencyService.saveResult(key, response);
            return response;

        } catch (Exception e) {
            // PaymentFailedException(결제 거절)도 여기서 키를 지운다 — 주문은 이미 CANCELLED로
            // 종결됐으므로 재호출은 상태 체크(PENDING 아님)에서 자연스럽게 막힌다.
            idempotencyService.delete(key);
            throw e;
        } catch (Error e) {
            idempotencyService.delete(key);
            throw e;
        }
    }

    /**
     * 토스 응답의 method(대분류) + easyPay.provider(간편결제 제공사)를 우리 PaymentMethod enum으로 매핑한다.
     *
     * 주의: "카드"/"계좌이체"/"간편결제"/"토스페이" 등 문자열이 실제 토스 응답과 정확히 일치하는지는
     * 토스 개발자센터 문서만으로 100% 확정하지 못했다 — 테스트 결제 한 번 찍어서 로그로 실제 값을
     * 확인하고, 다르면 이 switch문을 그 값으로 맞춰야 한다.
     */
    private PaymentMethod resolveMethod(String tossMethod, String easyPayProvider) {
        return switch (Objects.toString(tossMethod, "")) {
            case "카드" -> PaymentMethod.CARD;
            case "계좌이체" -> PaymentMethod.QUICK_TRANSFER;
            case "간편결제" -> switch (Objects.toString(easyPayProvider, "")) {
                case "토스페이" -> PaymentMethod.TOSS_PAY;
                case "페이코" -> PaymentMethod.PAYCO;
                case "카카오페이" -> PaymentMethod.KAKAO_PAY;
                case "네이버페이" -> PaymentMethod.NAVER_PAY;
                default -> PaymentMethod.ETC;
            };
            default -> PaymentMethod.ETC;
        };
    }
}
