package com.whale.order.domain.member.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.entity.WithdrawReason;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderItem;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SQLRestriction} 제거 후의 조회 가시성 검증.
 *
 * <p>탈퇴 회원은 로그인 경로에서만 숨겨져야 하고, 주문 이력이나 findById 에서는
 * 익명화된 상태로 조회돼야 한다. 애노테이션이 살아있으면 JOIN FETCH 가 INNER JOIN 으로
 * 걸리면서 탈퇴 회원의 주문이 목록에서 통째로 사라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawalVisibilityTest extends TestContainerBase {

    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;

    @Test
    @DisplayName("탈퇴 회원은 자체 로그인 조회에서 제외된다")
    void 탈퇴회원_로그인조회_제외() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        assertThat(memberRepository.findByUserIdAndIsDeletedFalse("chulsoo")).isPresent();

        // when
        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // then: 원래 아이디로는 찾히지 않는다
        assertThat(memberRepository.findByUserIdAndIsDeletedFalse("chulsoo")).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 KAKAO 회원은 카카오 로그인 조회에서 제외된다")
    void 탈퇴회원_카카오조회_제외() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());

        // when
        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // then: 새 회원으로 가입되도록 기존 레코드가 조회되지 않아야 한다
        assertThat(memberRepository.findByProviderAndProviderIdAndIsDeletedFalse(
                AuthProvider.KAKAO, "1234567890")).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 후 같은 아이디로 재가입할 수 있다 — 중복 검사가 탈퇴 회원까지 본다")
    void 탈퇴후_같은아이디_재가입_가능() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        // when
        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // then: 아이디 슬롯이 반납돼 중복이 아니다
        assertThat(memberRepository.existsByUserId("chulsoo")).isFalse();

        // 실제로 같은 아이디로 저장해도 unique 제약 위반이 없다
        Member rejoined = memberRepository.saveAndFlush(Member.builder()
                .userId("chulsoo").password("encoded2").name("김철수2")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        assertThat(rejoined.getMemberId()).isNotEqualTo(member.getMemberId());
    }

    @Test
    @DisplayName("탈퇴 회원의 과거 주문이 JOIN FETCH 목록에 '탈퇴한 회원'으로 남는다")
    void 탈퇴회원_주문_목록에_남음() {
        // given
        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        Member customer = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수").nickname("철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Store store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0)).build());
        Menu menu = menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500L).category(MenuCategory.BEVERAGE).build());

        Orders order = orderRepository.save(Orders.builder()
                .member(customer).store(store)
                .totalPrice(menu.getBasePrice()).orderType(OrderType.TAKEOUT).build());
        // findByIdWithDetails 는 orderItems 를 INNER JOIN FETCH 하므로 아이템이 최소 1건 필요하다
        order.addOrderItem(OrderItem.builder()
                .orders(order).menu(menu).quantity(1).unitPrice(menu.getBasePrice()).build());
        orderRepository.saveAndFlush(order);

        // when
        customer.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(customer);

        // then: 주문이 사라지지 않고 익명화된 이름으로 조회된다
        assertThat(orderRepository.findAllWithDetails())
                .extracting(o -> o.getMember().getName())
                .containsExactly("탈퇴한 회원");
        assertThat(orderRepository.findByIdWithDetails(order.getOrderId())).isPresent();
    }

    @Test
    @DisplayName("findById 는 탈퇴 회원도 반환한다")
    void findById_탈퇴회원_반환() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw(WithdrawReason.NOT_USING);
        memberRepository.saveAndFlush(member);

        // then: 탈퇴 처리·주문 조회 등이 findById 에 의존하므로 반환돼야 한다
        assertThat(memberRepository.findById(id)).isPresent();
        assertThat(memberRepository.findById(id).orElseThrow().isDeleted()).isTrue();
    }
}
