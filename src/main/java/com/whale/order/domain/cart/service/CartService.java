package com.whale.order.domain.cart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuOption;
import com.whale.order.domain.menu.repository.MenuOptionRepository;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.global.exception.DifferentStoreCartException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 장바구니 서비스.
 * Redis Hash(cart:{memberId})에 itemKey → CartItem(JSON) 형태로 저장하며 TTL 24시간이 지나면 자동 만료된다.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final Duration CART_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;

    /**
     * 장바구니에 메뉴를 담는다. 동일한 메뉴+옵션 조합이 이미 있으면 수량을 합산하고,
     * 가격은 담는 시점의 메뉴 가격으로 스냅샷한다.
     * force=false 기본값으로 호출 — 매장 충돌 시 예외를 던진다.
     */
    public CartResponse addItem(Long memberId, CartAddRequest request) {
        return addItem(memberId, request, false);
    }

    /**
     * 장바구니에 메뉴를 담는다.
     * force=true 일 경우, 기존 카트가 다른 매장 메뉴를 가지고 있어도 카트를 비우고 새 매장 메뉴를 담는다.
     * force=false 일 경우, 매장 충돌이면 {@link DifferentStoreCartException} 을 던져 클라이언트에 확인을 요구한다.
     */
    public CartResponse addItem(Long memberId, CartAddRequest request, boolean force) {
        Menu menu = menuRepository.findById(request.menuId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다: " + request.menuId()));

        validateRequiredOptionGroups(request);

        // 카트 키는 매장별로 분리되지 않으므로(키: cart:{memberId}) 매장이 섞이지 않도록 명시적으로 차단.
        // 사용자가 확인(force=true)하면 카트를 비우고 새 매장 메뉴를 담는다.
        ensureSingleStore(memberId, request.storeId(), force);

        String cartKey = cartKey(memberId);
        String itemKey = buildItemKey(request);

        // 옵션 추가금 계산 — overflow 명시 차단 (Math.addExact)
        long optionPrice = request.selectedOptions() == null ? 0L :
                request.selectedOptions().stream()
                        .mapToLong(CartAddRequest.SelectedOptionRequest::additionalPrice)
                        .reduce(0L, Math::addExact);
        long unitPrice = Math.addExact(menu.getBasePrice(), optionPrice);

        // 기존 항목이 있으면 수량 합산
        // 장바구니json
        String existing = (String) redisTemplate.opsForHash().get(cartKey, itemKey);
        int finalQuantity = request.quantity();
        if (existing != null) {
            CartItem prev = deserialize(existing);
            finalQuantity += prev.getQuantity();
        }

        // 재고 검증 — 합산 후 최종 수량 기준
        validateStock(request.storeId(), request.menuId(), menu.getName(), finalQuantity);

        List<CartItem.SelectedOption> selectedOptions = request.selectedOptions() == null ? List.of() :
                request.selectedOptions().stream()
                        .map(o -> new CartItem.SelectedOption(
                                o.menuOptionId(), o.optionGroup(), o.optionName(),
                                o.additionalPrice() != null ? o.additionalPrice() : 0L))
                        .toList();

        CartItem item = CartItem.builder()
                .itemKey(itemKey)
                .storeId(request.storeId())
                .menuId(menu.getMenuId())
                .menuName(menu.getName())
                .imageUrl(menu.getImageUrl())
                .basePrice(menu.getBasePrice())
                .quantity(finalQuantity)
                .selectedOptions(selectedOptions)
                .unitPrice(unitPrice)
                // unitPrice * quantity 도 overflow 가능 → Math.multiplyExact
                .totalPrice(Math.multiplyExact(unitPrice, (long) finalQuantity))
                .build();

        redisTemplate.opsForHash().put(cartKey, itemKey, serialize(item));
        redisTemplate.expire(cartKey, CART_TTL);

        return getCart(memberId);
    }

    /**
     * 장바구니를 조회한다. 항목은 메뉴명 기준으로 정렬되며 총 수량/총 금액을 함께 계산한다.
     */
    public CartResponse getCart(Long memberId) {
        String cartKey = cartKey(memberId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(cartKey);

        List<CartItem> items = entries.values().stream()
                .map(v -> deserialize((String) v))
                .sorted(Comparator.comparing(CartItem::getMenuName))
                .toList();

        // 합산 시도 overflow 차단
        long totalPrice = items.stream().mapToLong(CartItem::getTotalPrice).reduce(0L, Math::addExact);
        int totalCount = items.stream().mapToInt(CartItem::getQuantity).sum();

        return new CartResponse(items, totalPrice, totalCount);
    }

    /**
     * 장바구니 항목의 수량을 변경한다. quantity가 0 이하이면 항목을 삭제한다.
     * 증가 방향일 때만 재고 검증 — 감소는 항상 허용.
     */
    public CartResponse updateQuantity(Long memberId, String itemKey, int quantity) {
        if (quantity <= 0) {
            return removeItem(memberId, itemKey);
        }

        String cartKey = cartKey(memberId);
        String existing = (String) redisTemplate.opsForHash().get(cartKey, itemKey);
        if (existing == null) {
            throw new IllegalArgumentException("장바구니에 없는 항목입니다");
        }

        CartItem prev = deserialize(existing);

        // 수량 증가 시 재고 검증. storeId 가 없는 구버전 카트 항목은 스킵
        if (quantity > prev.getQuantity() && prev.getStoreId() != null) {
            validateStock(prev.getStoreId(), prev.getMenuId(), prev.getMenuName(), quantity);
        }

        CartItem updated = CartItem.builder()
                .itemKey(prev.getItemKey())
                .storeId(prev.getStoreId())
                .menuId(prev.getMenuId())
                .menuName(prev.getMenuName())
                .imageUrl(prev.getImageUrl())
                .basePrice(prev.getBasePrice())
                .quantity(quantity)
                .selectedOptions(prev.getSelectedOptions())
                .unitPrice(prev.getUnitPrice())
                .totalPrice(prev.getUnitPrice() * quantity)
                .build();

        redisTemplate.opsForHash().put(cartKey, itemKey, serialize(updated));
        redisTemplate.expire(cartKey, CART_TTL);
        return getCart(memberId);
    }

    /**
     * 장바구니에서 항목 하나를 삭제한다.
     */
    public CartResponse removeItem(Long memberId, String itemKey) {
        redisTemplate.opsForHash().delete(cartKey(memberId), itemKey);
        return getCart(memberId);
    }

    /**
     * 장바구니 전체를 삭제한다 (cart:{memberId} 키 자체를 제거).
     */
    public void clearCart(Long memberId) {
        redisTemplate.delete(cartKey(memberId));
    }

    // 기존 카트의 매장과 새 요청 매장이 다르면 차단. force=true 면 카트 비우고 진행.
    // 기존 항목 중 storeId 가 null 인 구버전 데이터(24h TTL 만료 전 이전 배포)는 검사에서 제외.
    private void ensureSingleStore(Long memberId, Long newStoreId, boolean force) {
        String cartKey = cartKey(memberId);
        Long existingStoreId = redisTemplate.opsForHash().values(cartKey).stream()
                .map(v -> deserialize((String) v))
                .map(CartItem::getStoreId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);

        if (existingStoreId == null || existingStoreId.equals(newStoreId)) return;

        if (force) {
            clearCart(memberId);
            return;
        }
        throw new DifferentStoreCartException(
                "장바구니에 이미 다른 매장의 메뉴가 담겨있습니다. 담으면 이전 매장의 메뉴는 삭제됩니다.");
    }

    // 해당 매장에 재고가 있는지(수량 > 0, 요청 수량 충족) 검증. quantity = -1 이면 무제한이라 패스
    private void validateStock(Long storeId, Long menuId, String menuName, int requestedQuantity) {
        Stock stock = stockRepository.findByStoreAndMenu(storeId, menuId)
                .orElseThrow(() -> new IllegalStateException("해당 매장에서 판매하지 않는 메뉴입니다: " + menuName));

        if (stock.getQuantity() < 0) return; // 무제한
        if (stock.getQuantity() == 0) {
            throw new IllegalStateException("품절된 메뉴입니다: " + menuName);
        }
        if (stock.getQuantity() < requestedQuantity) {
            throw new IllegalStateException(
                    menuName + " 재고가 부족합니다 (남은 재고: " + stock.getQuantity() + "개, 요청: " + requestedQuantity + "개)");
        }
    }

    // 메뉴의 필수 옵션 그룹이 모두 선택되었는지 검증
    private void validateRequiredOptionGroups(CartAddRequest request) {
        Set<String> requiredGroups = menuOptionRepository
                .findByMenu_MenuIdOrderByOptionGroup(request.menuId()).stream()
                .filter(o -> Boolean.TRUE.equals(o.getIsRequired()))
                .map(MenuOption::getOptionGroup)
                .collect(Collectors.toSet());

        if (requiredGroups.isEmpty()) return;

        Set<String> selectedGroups = request.selectedOptions() == null ? Set.of() :
                request.selectedOptions().stream()
                        .map(CartAddRequest.SelectedOptionRequest::optionGroup)
                        .collect(Collectors.toSet());

        Set<String> missing = requiredGroups.stream()
                .filter(g -> !selectedGroups.contains(g))
                .collect(Collectors.toSet());

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("다음 옵션은 필수로 선택해야 합니다: " + String.join(", ", missing));
        }
    }

    // menuId + 옵션ID 조합으로 고유 키 생성 (같은 메뉴 다른 옵션 구분)
    private String buildItemKey(CartAddRequest request) {
        String optionPart = request.selectedOptions() == null || request.selectedOptions().isEmpty()
                ? ""
                : request.selectedOptions().stream()
                        .map(o -> String.valueOf(o.menuOptionId()))
                        .sorted()
                        .collect(Collectors.joining(","));
        return request.menuId() + ":" + optionPart;
    }

    private String cartKey(Long memberId) {
        return CART_KEY_PREFIX + memberId;
    }

    private String serialize(CartItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("장바구니 직렬화 오류", e);
        }
    }

    private CartItem deserialize(String json) {
        try {
            return objectMapper.readValue(json, CartItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("장바구니 역직렬화 오류", e);
        }
    }
}
