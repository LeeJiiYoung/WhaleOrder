package com.whale.order.domain.stock.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class StockDemoService {

    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final StockRepository stockRepository;

    // 테스트용 데이터 초기화, 재고를 initialStock으로 리셋
    @Transactional
    public long[] setupTestData(int initialStock) {
        Member owner = memberRepository.findByUserId("demo-owner")
                .orElseGet(() -> memberRepository.save(Member.builder()
                        .userId("demo-owner")
                        .name("데모 점주")
                        .provider(AuthProvider.LOCAL)
                        .role(MemberRole.OWNER)
                        .build()));

        Store store = storeRepository.findAll().stream()
                .filter(s -> "데모 매장".equals(s.getName()))
                .findFirst()
                .orElseGet(() -> storeRepository.save(Store.builder()
                        .owner(owner)
                        .name("데모 매장")
                        .postalCode("12345")
                        .address("서울시 강남구 테스트로 1")
                        .openTime(LocalTime.of(9, 0))
                        .closeTime(LocalTime.of(21, 0))
                        .build()));

        Menu menu = menuRepository.findAll().stream()
                .filter(m -> "아메리카노(데모)".equals(m.getName()))
                .findFirst()
                .orElseGet(() -> menuRepository.save(Menu.builder()
                        .name("아메리카노(데모)")
                        .basePrice(4500L)
                        .category(MenuCategory.BEVERAGE)
                        .build()));

        Stock stock = stockRepository.findByStore_StoreIdAndMenu_MenuId(store.getStoreId(), menu.getMenuId())
                .orElseGet(() -> stockRepository.save(Stock.builder()
                        .store(store)
                        .menu(menu)
                        .quantity(initialStock)
                        .build()));

        stock.updateQuantity(initialStock);

        return new long[]{store.getStoreId(), menu.getMenuId()};
    }

    @Transactional(readOnly = true)
    public int getStock(long storeId, long menuId) {
        return stockRepository.findByStore_StoreIdAndMenu_MenuId(storeId, menuId)
                .map(Stock::getQuantity)
                .orElse(0);
    }
}
