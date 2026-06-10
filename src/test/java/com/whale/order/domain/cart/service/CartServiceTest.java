package com.whale.order.domain.cart.service;

import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장바구니 서비스 통합 테스트.
 *
 * <p>장바구니는 Redis Hash 구조로 저장된다.
 * <pre>
 *   Key   : "cart:{memberId}"
 *   Field : itemKey = "{menuId}:{optionId1,optionId2,...}"  (옵션 없으면 "{menuId}:")
 *   Value : CartItem JSON 직렬화
 * </pre>
 *
 * <p>itemKey 구조 덕분에 <b>같은 메뉴라도 옵션이 다르면 별도 항목</b>으로 관리되고,
 * 같은 메뉴+옵션 조합을 다시 담으면 수량이 합산된다.
 *
 * <p>Redis 격리: {@code @AfterEach}에서 해당 회원의 장바구니 키를 직접 삭제해
 * 테스트 간 상태가 섞이지 않도록 한다.
 * ({@code ddl-auto: create-drop}이 DB는 초기화하지만 Redis는 초기화하지 않는다.)
 *
 * <p>인프라: Testcontainers(PostgreSQL·Redis) + EmbeddedKafka.
 * CartService가 MenuRepository(JPA)에 의존하므로 PostgreSQL도 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class CartServiceTest extends TestContainerBase {

    @Autowired private CartService          cartService;
    @Autowired private MenuRepository       menuRepository;
    @Autowired private StringRedisTemplate  redisTemplate;

    private static final Long MEMBER_ID = 999L;

    private Menu menu;
    private Menu menu2;

    @BeforeEach
    void setUp() {
        menuRepository.deleteAll();

        menu = menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500).category(MenuCategory.BEVERAGE).build());
        menu2 = menuRepository.save(Menu.builder()
                .name("카페라테").basePrice(5500).category(MenuCategory.BEVERAGE).build());
    }

    @AfterEach
    void tearDown() {
        // 테스트마다 장바구니 Redis 키 초기화
        redisTemplate.delete("cart:" + MEMBER_ID);
    }

    @Test
    @DisplayName("메뉴를 장바구니에 추가하면 조회 시 포함된다")
    void 상품_추가_후_조회() {
        // when
        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 2, null));

        // then
        CartResponse cart = cartService.getCart(MEMBER_ID);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getMenuId()).isEqualTo(menu.getMenuId());
        assertThat(cart.items().get(0).getQuantity()).isEqualTo(2);
        assertThat(cart.totalPrice()).isEqualTo(4500 * 2);
    }

    @Test
    @DisplayName("같은 메뉴·같은 옵션을 두 번 추가하면 수량이 합산된다")
    void 같은_메뉴_같은_옵션_수량_합산() {
        // when
        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, null));
        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 2, null));

        // then: 1 + 2 = 3
        CartResponse cart = cartService.getCart(MEMBER_ID);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 메뉴라도 옵션이 다르면 별도 항목으로 저장된다")
    void 같은_메뉴_다른_옵션_별도_항목() {
        // when: 동일 메뉴, 다른 옵션ID
        var optionA = new CartAddRequest.SelectedOptionRequest(1L, "SIZE", "TALL",   0);
        var optionB = new CartAddRequest.SelectedOptionRequest(2L, "SIZE", "GRANDE", 500);

        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, List.of(optionA)));
        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, List.of(optionB)));

        // then: 별도 항목 2개
        CartResponse cart = cartService.getCart(MEMBER_ID);
        assertThat(cart.items()).hasSize(2);
        assertThat(cart.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("수량을 변경하면 총 금액도 함께 갱신된다")
    void 수량_변경() {
        // given
        CartResponse added = cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, null));
        String itemKey = added.items().get(0).getItemKey();

        // when
        CartResponse updated = cartService.updateQuantity(MEMBER_ID, itemKey, 3);

        // then
        assertThat(updated.items().get(0).getQuantity()).isEqualTo(3);
        assertThat(updated.totalPrice()).isEqualTo(4500 * 3);
    }

    @Test
    @DisplayName("수량을 0으로 변경하면 해당 항목이 삭제된다")
    void 수량_0이하_항목_삭제() {
        // given
        CartResponse added = cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 2, null));
        String itemKey = added.items().get(0).getItemKey();

        // when
        CartResponse result = cartService.updateQuantity(MEMBER_ID, itemKey, 0);

        // then
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("항목 삭제 후 장바구니에서 해당 메뉴가 사라진다")
    void 항목_삭제() {
        // given: 두 메뉴 추가
        CartResponse added = cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, null));
        cartService.addItem(MEMBER_ID, 추가요청(menu2.getMenuId(), 1, null));
        String itemKey = added.items().stream()
                .filter(i -> i.getMenuId().equals(menu.getMenuId()))
                .findFirst().orElseThrow().getItemKey();

        // when
        CartResponse result = cartService.removeItem(MEMBER_ID, itemKey);

        // then: 남은 항목은 menu2 하나
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).getMenuId()).isEqualTo(menu2.getMenuId());
    }

    @Test
    @DisplayName("장바구니 전체 초기화 후 비어있다")
    void 장바구니_초기화() {
        // given
        cartService.addItem(MEMBER_ID, 추가요청(menu.getMenuId(), 1, null));
        cartService.addItem(MEMBER_ID, 추가요청(menu2.getMenuId(), 2, null));

        // when
        cartService.clearCart(MEMBER_ID);

        // then
        CartResponse cart = cartService.getCart(MEMBER_ID);
        assertThat(cart.items()).isEmpty();
        assertThat(cart.totalPrice()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 추가 시 예외가 발생한다")
    void 존재하지않는_메뉴_추가_예외() {
        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, 추가요청(9999L, 1, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 메뉴");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────
    // CartAddRequest 생성 보일러플레이트를 줄이기 위한 팩토리 메서드.

    private CartAddRequest 추가요청(Long menuId, int quantity,
                                  List<CartAddRequest.SelectedOptionRequest> options) {
        return new CartAddRequest(menuId, quantity, options);
    }
}
