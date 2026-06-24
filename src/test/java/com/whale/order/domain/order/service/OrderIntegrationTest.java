package com.whale.order.domain.order.service;

import com.whale.order.support.TestContainerBase;
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
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 Kafka E2E 통합 테스트.
 *
 * <p>실제 Kafka 메시지 발행 → Consumer 처리 → DB 반영까지 전체 흐름을 검증한다.
 * 비즈니스 로직 단위 검증은 {@link SagaCompensationTest}에서 수행하며,
 * 이 테스트는 Kafka 경유 흐름의 정합성에 집중한다.
 *
 * <p>검증 항목
 * <ul>
 *   <li>재고 있는 주문 — Consumer가 재고를 차감하고 {@code stockDeducted=true}로 설정</li>
 *   <li>재고 부족 주문 — 보상 트랜잭션으로 주문·결제 모두 CANCELLED 처리</li>
 *   <li>다건 동시 처리 — 3건 동시 발행 시 재고가 정확히 3 차감</li>
 * </ul>
 *
 * <p>Consumer는 비동기로 동작하므로 결과를 폴링(최대 10초)해 확인한다.
 *
 * <p>인프라: Testcontainers(PostgreSQL·Redis) + EmbeddedKafka.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class OrderIntegrationTest extends TestContainerBase {

    @Autowired OrderKafkaProducer kafkaProducer;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentHistoryRepository paymentHistoryRepository;
    @Autowired StockRepository stockRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MemberRepository memberRepository;

    private Store store;
    private Menu menu;
    private Member customer;

    @BeforeEach
    void setUp() {
        // FK 순서대로 삭제
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        orderStatusHistoryRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        menuRepository.deleteAll();
        storeRepository.deleteAll();
        memberRepository.deleteAll();

        Member owner = memberRepository.save(Member.builder()
                .name("점주")
                .provider(AuthProvider.LOCAL)
                .role(MemberRole.OWNER)
                .build());

        customer = memberRepository.save(Member.builder()
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

    @Test
    @DisplayName("재고가 있으면 Kafka Consumer가 재고를 차감하고 stockDeducted를 true로 설정한다")
    void 재고있는_주문_Kafka처리_후_재고차감() throws InterruptedException {
        // given
        stockRepository.save(Stock.builder()
                .store(store)
                .menu(menu)
                .quantity(10)
                .build());

        Orders order = 주문_생성(1);
        결제_생성(order);

        // when
        kafkaProducer.publish(order.getOrderId());

        // then: Consumer 처리 완료까지 최대 10초 대기
        Long orderId = order.getOrderId();
        boolean stockDeducted = 처리완료까지대기(orderId);

        assertThat(stockDeducted).isTrue();
        int remaining = stockRepository
                .findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseThrow()
                .getQuantity();
        assertThat(remaining).isEqualTo(9);
    }

    @Test
    @DisplayName("재고가 부족하면 보상 트랜잭션이 실행되어 주문과 결제가 취소된다")
    void 재고부족_주문_보상트랜잭션_주문결제취소() throws InterruptedException {
        // given: 재고 0개
        stockRepository.save(Stock.builder()
                .store(store)
                .menu(menu)
                .quantity(0)
                .build());

        Orders order = 주문_생성(1);
        Payment payment = 결제_생성(order);

        // when
        kafkaProducer.publish(order.getOrderId());

        // then: 보상 트랜잭션 완료까지 최대 10초 대기
        Long orderId = order.getOrderId();
        Long paymentId = payment.getPaymentId();
        boolean cancelled = 취소완료까지대기(orderId);

        assertThat(cancelled).isTrue();
        Payment updatedPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("여러 주문이 동시에 들어와도 재고가 정확히 차감된다")
    void 다건_주문_동시처리_재고정합성() throws InterruptedException {
        // given: 재고 3개, 주문 3개
        stockRepository.save(Stock.builder()
                .store(store)
                .menu(menu)
                .quantity(3)
                .build());

        Orders order1 = 주문_생성(1);
        Orders order2 = 주문_생성(1);
        Orders order3 = 주문_생성(1);
        결제_생성(order1);
        결제_생성(order2);
        결제_생성(order3);

        // when: 3개 동시 발행
        kafkaProducer.publish(order1.getOrderId());
        kafkaProducer.publish(order2.getOrderId());
        kafkaProducer.publish(order3.getOrderId());

        // then: 3개 모두 처리될 때까지 대기 (최대 15초)
        for (int i = 0; i < 30; i++) {
            Thread.sleep(500);
            long processed = orderRepository.findAllById(
                    java.util.List.of(order1.getOrderId(), order2.getOrderId(), order3.getOrderId())
            ).stream().filter(Orders::isStockDeducted).count();
            if (processed == 3) break;
        }

        int remaining = stockRepository
                .findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseThrow()
                .getQuantity();
        assertThat(remaining).isEqualTo(0);
    }

    // --- 헬퍼 메서드 ---

    private Orders 주문_생성(int quantity) {
        Orders order = orderRepository.save(Orders.builder()
                .member(customer)
                .store(store)
                .totalPrice(menu.getBasePrice() * quantity)
                .orderType(OrderType.TAKEOUT)
                .build());

        OrderItem item = OrderItem.builder()
                .orders(order)
                .menu(menu)
                .quantity(quantity)
                .unitPrice(menu.getBasePrice())
                .build();
        order.addOrderItem(item);
        return orderRepository.save(order);
    }

    private Payment 결제_생성(Orders order) {
        Payment payment = Payment.builder()
                .orders(order)
                .member(customer)
                .amount(order.getTotalPrice())
                .method(PaymentMethod.CREDIT_CARD)
                .build();
        payment.success("MOCK-" + order.getOrderId());
        return paymentRepository.save(payment);
    }

    // stockDeducted = true 가 될 때까지 최대 10초 폴링
    private boolean 처리완료까지대기(Long orderId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            if (orderRepository.findById(orderId).orElseThrow().isStockDeducted()) return true;
        }
        return false;
    }

    // 주문 상태가 CANCELLED 가 될 때까지 최대 10초 폴링
    private boolean 취소완료까지대기(Long orderId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            if (orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.CANCELLED) return true;
        }
        return false;
    }
}
