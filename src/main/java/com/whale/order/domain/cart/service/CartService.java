package com.whale.order.domain.cart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.dto.CartItem;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;

    /**
     * 장바구니에 메뉴를 담는다. 동일한 메뉴+옵션 조합이 이미 있으면 수량을 합산하고,
     * 가격은 담는 시점의 메뉴 가격으로 스냅샷한다.
     */
    public CartResponse addItem(Long memberId, CartAddRequest request) {
        Menu menu = menuRepository.findById(request.menuId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다: " + request.menuId()));

        String cartKey = cartKey(memberId);
        String itemKey = buildItemKey(request);

        // 옵션 추가금 계산
        int optionPrice = request.selectedOptions() == null ? 0 :
                request.selectedOptions().stream()
                        .mapToInt(CartAddRequest.SelectedOptionRequest::additionalPrice)
                        .sum();
        int unitPrice = menu.getBasePrice() + optionPrice;

        // 기존 항목이 있으면 수량 합산
        // 장바구니json
        String existing = (String) redisTemplate.opsForHash().get(cartKey, itemKey);
        int finalQuantity = request.quantity();
        if (existing != null) {
            CartItem prev = deserialize(existing);
            finalQuantity += prev.getQuantity();
        }

        List<CartItem.SelectedOption> selectedOptions = request.selectedOptions() == null ? List.of() :
                request.selectedOptions().stream()
                        .map(o -> new CartItem.SelectedOption(
                                o.menuOptionId(), o.optionGroup(), o.optionName(), o.additionalPrice()))
                        .toList();

        CartItem item = CartItem.builder()
                .itemKey(itemKey)
                .menuId(menu.getMenuId())
                .menuName(menu.getName())
                .imageUrl(menu.getImageUrl())
                .basePrice(menu.getBasePrice())
                .quantity(finalQuantity)
                .selectedOptions(selectedOptions)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice * finalQuantity)
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

        int totalPrice = items.stream().mapToInt(CartItem::getTotalPrice).sum();
        int totalCount = items.stream().mapToInt(CartItem::getQuantity).sum();

        return new CartResponse(items, totalPrice, totalCount);
    }

    /**
     * 장바구니 항목의 수량을 변경한다. quantity가 0 이하이면 항목을 삭제한다.
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
        CartItem updated = CartItem.builder()
                .itemKey(prev.getItemKey())
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
