package com.whale.order.domain.order.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.repository.OrderStatusHistoryRepository;
import com.whale.order.domain.payment.entity.Payment;
import com.whale.order.domain.payment.entity.PaymentMethod;
import com.whale.order.domain.payment.entity.PaymentStatus;
import com.whale.order.domain.payment.repository.PaymentHistoryRepository;
import com.whale.order.domain.payment.repository.PaymentRepository;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.stock.repository.StockRestoreFailureRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Saga 보상 트랜잭션 단위 테스트.
 *
 * Saga 패턴 흐름:
 *   결제 성공 → Kafka 발행 → Consumer가 재고 차감 시도
 *     ├─ 성공: stockDeducted = true, 주문 정상 진행
 *     └─ 실패(재고 부족 등): 보상 트랜잭션 실행
 *         → 주문 CANCELLED + 결제 CANCELLED (환불)
 *
 * Kafka 3회 재시도 후에도 실패하면 DLT(Dead Letter Topic)로 이동되고
 * OrderKafkaConsumer.consumeDlt()가 compensate()를 직접 호출한다.
 *
 * 이 테스트는 Kafka를 거치지 않고 process() / compensate()를 직접 호출해
 * 모든 보상 분기를 빠르게 검증한다.
 * Kafka 경유 E2E 흐름은 OrderIntegrationTest에서 별도로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
// KafkaConfig가 kafka.enabled 미설정 시 자동 로드되므로 EmbeddedKafka 필요
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class SagaCompensationTest extends TestContainerBase {

    @Autowired private OrderProcessingService       orderProcessingService;
    @Autowired private OrderRepository              orderRepository;
    @Autowired private OrderStatusHistoryRepository orderHistoryRepository;
    @Autowired private PaymentRepository            paymentRepository;
    @Autowired private PaymentHistoryRepository     paymentHistoryRepository;
    @Autowired private StockRepository              stockRepository;
    @Autowired private StockRestoreFailureRepository stockRestoreFailureRepository;
    @Autowired private MemberRepository             memberRepository;
    @Autowired private StoreRepository              storeRepository;
    @Autowired private MenuRepository               menuRepository;

    private Member member;
    private Store  store;
    private Menu   menu;

    @BeforeEach
    void setUp() {
        // payment_history → payment → order_status_history → orders(+order_item cascade)
        // → stock → menu → store → member 순으로 FK 의존성 역순 삭제
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderHistoryRepository.deleteAll();
        stockRestoreFailureRepository.deleteAll();
        orderRepository.deleteAll(); // order_item은 Orders.cascade = ALL로 함께 삭제
        stockRepository.deleteAll();
        menuRepository.deleteAll();
        storeRepository.deleteAll();
        memberRepository.deleteAll();

        Member owner = memberRepository.save(Member.builder()
                .name("점주")
                .provider(AuthProvider.LOCAL)
                .role(MemberRole.OWNER)
                .build());

        member = memberRepository.save(Member.builder()
                .name("고객")
                .provider(AuthProvider.LOCAL)
                .role(MemberRole.CUSTOMER)
                .build());

        store = storeRepository.save(Store.builder()
                .owner(owner)
                .name("테스트 매장")
                .postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0))
                .closeTime(LocalTime.of(21, 0))
                .build());

        menu = menuRepository.save(Menu.builder()
                .name("아메리카노")
                .basePrice(4500L)
                .category(MenuCategory.BEVERAGE)
                .build());
    }

    // ── 재고 부족 시 보상 트랜잭션 ──────────────────────────────────────────

    @Test
    @DisplayName("재고 부족 시 주문이 CANCELLED로 변경된다")
    void 재고부족_process_주문_CANCELLED() {
        // given: 재고 0 → stockLockFacade.deductStock() 내부에서 IllegalStateException 발생
        재고_설정(0);
        Orders order = 주문_생성();

        // when: Kafka Consumer가 orderId를 받아 process() 호출하는 상황을 직접 재현
        orderProcessingService.process(order.getOrderId());

        // then: 보상 트랜잭션으로 주문 취소
        Orders found = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("재고 부족 시 연결된 결제가 Saga 보상 트랜잭션으로 CANCELLED 처리된다")
    void 재고부족_process_결제_보상취소() {
        // given: 재고 0, 결제는 이미 PG 승인 완료(SUCCESS) 상태
        //        → 주문 취소 시 PG 취소 API 호출해야 하는 시나리오
        재고_설정(0);
        Orders order    = 주문_생성();
        Payment payment = 결제_생성_성공(order);

        // when
        orderProcessingService.process(order.getOrderId());

        // then: Payment.cancel() 호출 → CANCELLED + 취소 사유 저장
        Payment found = paymentRepository.findById(payment.getPaymentId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(found.getFailedReason()).contains("재고 부족");
    }

    // ── DLT 시나리오: 3회 재시도 후에도 실패 → compensate() 직접 호출 ────

    @Test
    @DisplayName("DLT 수신 시 compensate()가 주문과 결제를 원자적으로 취소한다")
    void DLT_compensate_주문결제_동시_취소() {
        // given: 정상 주문 + 성공 결제
        //        Kafka Consumer가 3회 재시도 후에도 처리 실패 → DLT 이동 시나리오
        Orders order    = 주문_생성();
        Payment payment = 결제_생성_성공(order);

        // when: OrderKafkaConsumer.consumeDlt()가 compensate() 호출하는 상황 재현
        orderProcessingService.compensate(order.getOrderId());

        // then: 주문 + 결제 모두 취소
        Orders  foundOrder   = orderRepository.findById(order.getOrderId()).orElseThrow();
        Payment foundPayment = paymentRepository.findById(payment.getPaymentId()).orElseThrow();
        assertThat(foundOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 CANCELLED 상태인 주문은 compensate() 재호출 시 스킵되고 예외 없음")
    void 이미_CANCELLED_주문_compensate_멱등성() {
        // given: 이미 취소된 주문 — 네트워크 재전송 등으로 DLT 메시지가 중복 도착하는 경우
        Orders order = 주문_생성();
        order.cancel();
        orderRepository.save(order);

        // when & then: cancelOrder() 내부에서 CANCELLED 상태 감지 후 return → 예외 없이 종료
        assertThatCode(() -> orderProcessingService.compensate(order.getOrderId()))
                .doesNotThrowAnyException();

        Orders found = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    // ── 정상 처리 경로 ──────────────────────────────────────────────────

    @Test
    @DisplayName("재고 충분 시 process()가 재고를 차감하고 stockDeducted를 true로 설정한다")
    void 재고충분_process_성공_stockDeducted_true() {
        // given: 재고 10개, 주문 수량 1개
        재고_설정(10);
        Orders order = 주문_생성();

        // when
        orderProcessingService.process(order.getOrderId());

        // then: stockDeducted = true → 이후 주문 취소 시 재고 복구 여부 판단에 사용
        Orders found = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(found.isStockDeducted()).isTrue();

        int remaining = stockRepository
                .findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseThrow()
                .getQuantity();
        assertThat(remaining).isEqualTo(9);
    }

    @Test
    @DisplayName("이미 CANCELLED 상태인 주문은 process() 호출 시 재고 차감 없이 스킵된다")
    void 이미_CANCELLED_주문_process_스킵() {
        // given: 고객이 주문 직후 취소 → Consumer 처리 전에 이미 CANCELLED인 경우
        재고_설정(10);
        Orders order = 주문_생성();
        order.cancel();
        orderRepository.save(order);

        // when: processOrder() 첫 줄에서 CANCELLED 감지 → 즉시 return
        orderProcessingService.process(order.getOrderId());

        // then: 재고 차감 없음
        int remaining = stockRepository
                .findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseThrow()
                .getQuantity();
        assertThat(remaining).isEqualTo(10);
    }

    // ── 헬퍼 메서드 ──────────────────────────────────────────────────────

    private void 재고_설정(int quantity) {
        stockRepository.save(Stock.builder()
                .store(store)
                .menu(menu)
                .quantity(quantity)
                .build());
    }

    private Orders 주문_생성() {
        // Orders를 먼저 저장한 뒤 OrderItem을 추가하고 재저장해야
        // order_id FK가 채워진 상태로 order_item이 insert된다
        Orders order = orderRepository.save(Orders.builder()
                .member(member)
                .store(store)
                .totalPrice(menu.getBasePrice())
                .orderType(OrderType.TAKEOUT)
                .build());

        OrderItem item = OrderItem.builder()
                .orders(order)
                .menu(menu)
                .quantity(1)
                .unitPrice(menu.getBasePrice())
                .build();
        order.addOrderItem(item);
        return orderRepository.save(order);
    }

    private Payment 결제_생성_성공(Orders order) {
        Payment payment = Payment.builder()
                .orders(order)
                .member(member)
                .amount(order.getTotalPrice())
                .method(PaymentMethod.CREDIT_CARD)
                .build();
        // PG사 승인 완료 상태로 설정 — 취소 시 Payment.cancel()이 SUCCESS 여부를 검증함
        payment.success("MOCK-TX-" + order.getOrderId());
        return paymentRepository.save(payment);
    }
}
