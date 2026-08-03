package com.whale.order.domain.member.service;

import com.whale.order.domain.member.dto.WithdrawRequest;
import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.entity.WithdrawReason;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.auth.RefreshTokenService;
import com.whale.order.global.exception.WithdrawNotAllowedException;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴 서비스 통합 테스트.
 *
 * <p>검증 범위: 익명화, Redis 정리(리프레시 토큰·장바구니),
 * 차단 조건 3가지(비밀번호 불일치 / 진행 중 주문 / 역할).
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawTest extends TestContainerBase {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private Store store;

    @BeforeEach
    void setUpFixtures() {
        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0)).build());
        menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500L).category(MenuCategory.BEVERAGE).build());
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 맞게 입력하면 익명화되고 Redis 데이터가 정리된다")
    void LOCAL_탈퇴_성공() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();
        // 정리 대상이 실제로 존재해야 삭제됐는지 확인할 수 있다.
        // 장바구니는 CartService.addItem 대신 Redis 키를 직접 심어 Menu·Stock 픽스처를 피한다.
        refreshTokenService.save(id, "dummy-refresh-token");
        redisTemplate.opsForHash().put("cart:" + id, "dummy-item", "{}");
        assertThat(redisTemplate.hasKey("cart:" + id)).isTrue();

        // when
        memberService.withdraw(id, new WithdrawRequest("pw1234!", WithdrawReason.NOT_USING));

        // then: 익명화
        Member found = memberRepository.findById(id).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getUserId()).isEqualTo("deleted_" + id);
        assertThat(found.getName()).isEqualTo("탈퇴한 회원");
        assertThat(found.getNickname()).isNull();
        assertThat(found.getPhone()).isNull();

        // then: 탈퇴 사유·시각 기록
        assertThat(found.getWithdrawReason()).isEqualTo(WithdrawReason.NOT_USING);
        assertThat(found.getWithdrawnAt()).isNotNull();

        // then: Redis 정리 (cart: 접두사는 CartService.CART_KEY_PREFIX 와 동일)
        assertThat(refreshTokenService.get(id)).isNull();
        assertThat(redisTemplate.hasKey("cart:" + id)).isFalse();
    }

    @Test
    @DisplayName("KAKAO 회원은 비밀번호 없이 탈퇴할 수 있다")
    void KAKAO_탈퇴_성공() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when: body 자체가 없는 요청을 재현
        memberService.withdraw(id, null);

        // then
        Member found = memberRepository.findById(id).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getProviderId()).isEqualTo("deleted_" + id);
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 틀리면 탈퇴되지 않는다")
    void 비밀번호_불일치_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(id, new WithdrawRequest("wrong-password", WithdrawReason.NOT_USING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비밀번호가 맞지 않습니다");
        assertThat(memberRepository.findById(id).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 보내지 않으면 탈퇴되지 않는다")
    void 비밀번호_누락_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(id, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비밀번호가 맞지 않습니다");
    }

    @Test
    @DisplayName("PENDING 주문이 있으면 탈퇴할 수 없다")
    void 진행중_PENDING_주문_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.PENDING);

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!", WithdrawReason.NOT_USING)))
                .isInstanceOf(WithdrawNotAllowedException.class)
                .hasMessageContaining("진행 중인 주문");
    }

    @Test
    @DisplayName("PREPARING 주문이 있으면 탈퇴할 수 없다")
    void 진행중_PREPARING_주문_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.PREPARING);

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!", WithdrawReason.NOT_USING)))
                .isInstanceOf(WithdrawNotAllowedException.class)
                .hasMessageContaining("진행 중인 주문");
    }

    @Test
    @DisplayName("완료·취소된 주문만 있으면 탈퇴할 수 있다")
    void 완료된_주문만_있으면_탈퇴_성공() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.COMPLETED);
        주문_생성(member, OrderStatus.CANCELLED);

        // when
        memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!", WithdrawReason.NOT_USING));

        // then
        assertThat(memberRepository.findById(member.getMemberId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("OWNER 는 셀프 탈퇴할 수 없다")
    void OWNER_셀프탈퇴_차단() {
        // given
        Member owner = memberRepository.save(Member.builder()
                .userId("owner1").password(passwordEncoder.encode("pw1234!")).name("점주2")
                .provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(owner.getMemberId(), new WithdrawRequest("pw1234!", WithdrawReason.NOT_USING)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(memberRepository.findById(owner.getMemberId()).orElseThrow().isDeleted()).isFalse();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private Member 고객_생성(String userId, String rawPassword) {
        return memberRepository.save(Member.builder()
                .userId(userId).password(passwordEncoder.encode(rawPassword))
                .name("김철수").nickname("철수").phone("010-1234-5678")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
    }

    private void 주문_생성(Member member, OrderStatus status) {
        Orders order = orderRepository.save(Orders.builder()
                .member(member).store(store)
                .totalPrice(4500L).orderType(OrderType.TAKEOUT).build());
        if (status == OrderStatus.PREPARING) {
            order.startPreparing();
        } else if (status == OrderStatus.COMPLETED) {
            order.startPreparing();
            order.complete();
        } else if (status == OrderStatus.CANCELLED) {
            order.cancel();
        }
        orderRepository.save(order);
    }
}
