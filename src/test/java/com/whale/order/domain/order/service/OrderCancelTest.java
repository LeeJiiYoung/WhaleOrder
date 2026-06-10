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
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 고객 주문 취소 통합 테스트.
 *
 * <p>검증 핵심: {@code stockDeducted} 플래그에 따른 재고 복구 분기
 * <ul>
 *   <li>{@code stockDeducted=false} — Kafka Consumer가 아직 재고를 차감하지 않은 상태.
 *       취소해도 재고는 그대로다.</li>
 *   <li>{@code stockDeducted=true}  — Consumer가 재고를 이미 차감한 상태.
 *       취소 시 {@link com.whale.order.domain.stock.service.StockLockFacade}를 통해
 *       Redis 분산 락 하에 재고가 복구된다.</li>
 * </ul>
 *
 * <p>취소 가능 조건: {@code PENDING} 상태에서만 가능.
 * {@code PREPARING} 이후는 {@link IllegalStateException}이 발생한다.
 *
 * <p>인프라: Testcontainers(PostgreSQL·Redis) + EmbeddedKafka.
 * KafkaConfig가 컨텍스트 로드 시 자동 등록되므로 {@code @EmbeddedKafka} 없이는
 * 브로커 연결 오류가 발생해 스프링 컨텍스트 자체가 뜨지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class OrderCancelTest extends TestContainerBase {

    @Autowired
    private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderStatusHistoryRepository historyRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;

    private Member customer;
    private Member other;
    private Store store;
    private Menu menu;

    // ── 픽스처 ────────────────────────────────────────────────────────
    // FK 역순(자식 → 부모)으로 삭제해야 제약 위반 없이 초기화된다.

    // BeforeEach: Test 실행되기 전마다 자동호출됨
    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        menuRepository.deleteAll();
        storeRepository.deleteAll();
        memberRepository.deleteAll();

        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());

        customer = memberRepository.save(Member.builder()
                .name("고객").provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        other = memberRepository.save(Member.builder()
                .name("타인").provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0))
                .build());

        menu = menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500).category(MenuCategory.BEVERAGE).build());
    }

    @Test
    @DisplayName("재고 미차감 PENDING 주문 취소 시 재고 변화 없이 CANCELLED 처리된다")
    void 재고미차감_PENDING_취소_재고복구_없음() {
        // given: 재고 10개, stockDeducted=false (Kafka 미처리 상태)
        재고_설정(10);
        Orders order = 주문_생성(false);

        // when
        orderService.cancelOrder(order.getOrderId(), customer.getMemberId());

        // then: 주문 취소 + 재고 그대로
        Orders found = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(재고조회()).isEqualTo(10);
    }

    @Test
    @DisplayName("재고 차감 완료된 PENDING 주문 취소 시 재고가 복구된다")
    void 재고차감완료_PENDING_취소_재고복구됨() {
        // given: 재고 9개 (Kafka가 1개 차감 완료 상태), stockDeducted=true
        재고_설정(9);
        Orders order = 주문_생성(true);

        // when
        orderService.cancelOrder(order.getOrderId(), customer.getMemberId());

        // then: 주문 취소 + 차감된 1개가 복구되어 다시 10개
        Orders found = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(재고조회()).isEqualTo(10);
    }

    @Test
    @DisplayName("PREPARING 상태 주문은 취소할 수 없다")
    void PREPARING_상태_취소_예외발생() {
        // given: 재고 차감 완료 후 관리자가 제조 시작한 주문
        재고_설정(10);
        Orders order = 주문_생성(true);
        order.startPreparing();
        orderRepository.save(order);

        // when & then
        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId(), customer.getMemberId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("접수 대기 중인 주문만 취소할 수 있습니다");
    }

    @Test
    @DisplayName("다른 회원의 주문은 취소할 수 없다")
    void 타인_주문_취소_예외발생() {
        // given
        Orders order = 주문_생성(false);

        // when & then
        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId(), other.getMemberId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본인 주문만 취소할 수 있습니다");
    }

    @Test
    @DisplayName("주문 취소 시 상태 이력이 CANCELLED로 기록된다")
    void 주문_취소_이력_기록됨() {
        // given
        Orders order = 주문_생성(false);

        // when
        orderService.cancelOrder(order.getOrderId(), customer.getMemberId());

        // then
        boolean hasCancelledHistory = historyRepository
                .findAll().stream()
                .anyMatch(h -> h.getOrders().getOrderId().equals(order.getOrderId())
                        && h.getStatus() == OrderStatus.CANCELLED);
        assertThat(hasCancelledHistory).isTrue();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────
    // 테스트 데이터 생성 전용 메서드. 프로덕션 코드를 우회하지 않고
    // 레포지토리에 직접 저장해 특정 상태(stockDeducted 등)를 빠르게 셋업한다.

    private void 재고_설정(int quantity) {
        stockRepository.save(Stock.builder()
                .store(store).menu(menu).quantity(quantity).build());
    }

    private int 재고조회() {
        return stockRepository.findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseThrow().getQuantity();
    }

    private Orders 주문_생성(boolean stockDeducted) {
        Orders order = orderRepository.save(Orders.builder()
                .member(customer).store(store)
                .totalPrice(menu.getBasePrice()).orderType(OrderType.TAKEOUT)
                .build());

        OrderItem item = OrderItem.builder()
                .orders(order).menu(menu).quantity(1).unitPrice(menu.getBasePrice()).build();
        order.addOrderItem(item);

        if (stockDeducted) order.markStockDeducted();
        return orderRepository.save(order);
    }
}
