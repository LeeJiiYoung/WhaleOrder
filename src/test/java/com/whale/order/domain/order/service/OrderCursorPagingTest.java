package com.whale.order.domain.order.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.response.CursorPageResponse;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내 주문 목록 커서 페이징 통합 테스트.
 *
 * <p>{@code (created_at, order_id)} 복합 커서가 실제 PostgreSQL 에서 의도대로 동작하는지 검증한다.
 * 특히 Querydsl {@code booleanTemplate} 로 만든 행 값 비교 {@code (a, b) < (?, ?)} 는
 * PostgreSQL 고유 문법이라 컴파일만으로는 검증되지 않으므로, 실제 DB 실행이 필요하다.
 *
 * <p>검증 항목
 * <ul>
 *   <li>첫 페이지 — 최신순 정렬, hasNext/nextCursor 세팅</li>
 *   <li>커서 순회 — 전체 주문을 중복·누락 없이 정확히 한 번씩 조회</li>
 *   <li><b>createdAt 동률</b> — 모든 주문의 생성 시각이 같아도 orderId tie-breaker 로 정상 페이징</li>
 *   <li>마지막 페이지 — hasNext=false, nextCursor=null</li>
 *   <li>타인 주문 격리 — 커서 OR 조건이 memberId 필터를 무력화하지 않음</li>
 *   <li>잘못된 커서 — 조작된 문자열은 예외로 차단</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class OrderCursorPagingTest extends TestContainerBase {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Member customer;
    private Member otherCustomer;
    private Store store;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());

        customer = memberRepository.save(Member.builder()
                .name("고객").provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        otherCustomer = memberRepository.save(Member.builder()
                .name("다른고객").provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        store = storeRepository.save(Store.builder()
                .owner(owner)
                .name("테스트 매장")
                .postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0))
                .closeTime(LocalTime.of(21, 0))
                .build());
    }

    @Test
    @DisplayName("첫 페이지는 최신순으로 size만큼 반환하고 다음 커서를 함께 내려준다")
    void 첫_페이지_조회() {
        주문_생성(7, customer);

        CursorPageResponse<OrderResponse> page = orderService.getMyOrders(customer.getMemberId(), null, 3);

        assertThat(page.content()).hasSize(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();

        // 최신순 = orderId 내림차순 (7, 6, 5)
        assertThat(page.content()).extracting(OrderResponse::orderId)
                .containsExactly(7L, 6L, 5L);
    }

    @Test
    @DisplayName("커서를 따라 끝까지 순회하면 전체 주문을 중복·누락 없이 정확히 한 번씩 조회한다")
    void 커서_순회_전체조회() {
        int total = 17;
        주문_생성(total, customer);

        List<Long> collected = 커서_끝까지_순회(customer.getMemberId(), 5);

        assertThat(collected).hasSize(total);
        assertThat(collected).doesNotHaveDuplicates();
        // 17 → 1 내림차순
        assertThat(collected).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("모든 주문의 생성 시각이 동일해도 orderId tie-breaker 덕에 누락·중복이 없다")
    void createdAt_동률_페이징() {
        int total = 12;
        주문_생성(total, customer);

        // 복합 커서의 존재 이유를 정면으로 검증 — created_at 을 전부 같은 값으로 덮어쓴다.
        // 단일 커서(created_at only)였다면 여기서 동률 구간이 통째로 누락되거나 무한 반복된다.
        jdbcTemplate.update("UPDATE orders SET created_at = TIMESTAMP '2026-07-27 14:03:22.481'");

        List<Long> collected = 커서_끝까지_순회(customer.getMemberId(), 5);

        assertThat(collected).hasSize(total);
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("마지막 페이지는 hasNext=false, nextCursor=null 을 반환한다")
    void 마지막_페이지() {
        주문_생성(4, customer);

        CursorPageResponse<OrderResponse> first = orderService.getMyOrders(customer.getMemberId(), null, 3);
        assertThat(first.hasNext()).isTrue();

        CursorPageResponse<OrderResponse> last =
                orderService.getMyOrders(customer.getMemberId(), first.nextCursor(), 3);

        assertThat(last.content()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서 조건이 있어도 타인의 주문은 조회되지 않는다 (OR 괄호 누락 방어)")
    void 타인_주문_격리() {
        주문_생성(5, customer);
        주문_생성(5, otherCustomer);

        List<Long> mine = 커서_끝까지_순회(customer.getMemberId(), 2);

        assertThat(mine).hasSize(5);
        List<Long> myOrderIds = orderRepository.findAll().stream()
                .filter(o -> o.getMember().getMemberId().equals(customer.getMemberId()))
                .map(Orders::getOrderId)
                .toList();
        assertThat(mine).containsExactlyInAnyOrderElementsOf(myOrderIds);
    }

    @Test
    @DisplayName("조작되거나 손상된 커서는 예외로 차단한다")
    void 잘못된_커서() {
        주문_생성(3, customer);

        assertThatThrownBy(() -> orderService.getMyOrders(customer.getMemberId(), "!!!not-a-cursor!!!", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("잘못된 커서");
    }

    // ── helper ────────────────────────────────────────────────

    /** 커서를 따라 마지막 페이지까지 순회하며 조회된 orderId 를 순서대로 모은다. */
    private List<Long> 커서_끝까지_순회(Long memberId, int size) {
        List<Long> collected = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;

        // 무한 루프 방어 — 정상이라면 total/size + 1 회 이내에 끝난다
        for (int guard = 0; hasNext && guard < 100; guard++) {
            CursorPageResponse<OrderResponse> page = orderService.getMyOrders(memberId, cursor, size);
            page.content().forEach(o -> collected.add(o.orderId()));
            cursor = page.nextCursor();
            hasNext = page.hasNext();
        }
        return collected;
    }

    private void 주문_생성(int count, Member member) {
        for (int i = 0; i < count; i++) {
            orderRepository.save(Orders.builder()
                    .member(member)
                    .store(store)
                    .totalPrice(4500L)
                    .orderType(OrderType.TAKEOUT)
                    .customerRequest("요청" + i)
                    .build());
        }
    }
}
