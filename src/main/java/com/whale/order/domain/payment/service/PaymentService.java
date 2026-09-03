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
    private final KafkaOutboxService kafkaOutboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TossPaymentClient tossPaymentClient;

    // 토스 orderId는 6~64자 영문/숫자/-/_ 만 허용 — 우리 내부 PK(Long, 1~2자리도 흔함)를
    // 그대로 못 쓰므로 접두사를 붙여 토스 전용 문자열을 따로 만든다. DB엔 여전히 내부 PK만 저장.
    private static final String TOSS_ORDER_ID_PREFIX = "whale-";

    /* ★ 결제 요청 시
    1. 장바구니 조회
    2. 장바구니 금액 <> 장바구니 담을때 금액 일때 에러 (클라조작)
    3. 장바구니 가지고 멱등성 키 구함
    4. 처리중인 멱등성 키 없을때만 레디스에 키 넣음(SET NX)
    매장이 영업중이고 매장에 메뉴가 판매중일때만
    5. 주문 아이템들 for문 돌면서 order 에 추가
    6. order 를 db에 저장
    7. 결제 pending으로 db에 저장
    8-1. Mock결제 (90% 만 성공)
    8-2. 결제 성공 시 이력과 함께 db에 기록
    8-3. 스프링 애플리케이션 이벤트 발행. 이후 카프ㅎ카
    8-4. 결제처리횟수 1 올림 (로그분석용)
    9-1. Mock결제 실패시
    9-2. 결제이력 db에 기록
    9-3. 주문취소
    9-4. 주문이력 db에 기록
    9-5. 결제실패횟수 1 올림 (로그분석용)
    10. pay() 커밋 (발행예약) -> orderCreatedEvent -> OrderEventListener가 publish(발행) -> OrderKafkaConsumer가 발행된거 확인(kafkaListener) 후 처리 -> OrderProcessingService
    11. 재고차감
    12. 차감 로직 전 차감플래그가 true 이거나 캔슬된 주문인지 검사. 이러면 동작안함
    13. 메뉴 for문 돌면서 재고 차감. 모두 차감 후 차감플래그=true 저장
    14. 모두 처리 후 고객, 매장 대시보드에 sse 알림
    */

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

        //이미 redis 에 중복 key가 들어있고 처리중인 경우
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

                // Outbox 도입: 커밋과 동일 트랜잭션에서 발행 예약 → 원자성 확보.
                // 이 호출을 try-catch 로 감싸면 원자성 깨짐. 예외는 그대로 상위로 던져야 함.
                String outboxPayload = "{\"orderId\":" + order.getOrderId() + "}";
                kafkaOutboxService.enqueue("order-created", order.getOrderId(), outboxPayload);

                // 장바구니 삭제는 여전히 AFTER_COMMIT 리스너에서 처리 (Redis 작업, outbox 대상 아님).
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
        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 결제할 수 있습니다");
        }
        Payment payment = paymentRepository.findByOrders(order)
                .orElseThrow(() -> new IllegalArgumentException("결제 정보를 찾을 수 없습니다"));

        // 새로고침 등으로 confirm이 중복 호출된 경우 — 토스에 재승인 요청을 보내지 않고 그대로 반환
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return PaymentResponse.from(payment, order, 0);
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

        return PaymentResponse.from(payment, order, 0);
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
