package com.whale.order.domain.stock.service;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.dto.StockResponse;
import com.whale.order.domain.stock.dto.StockUpdateRequest;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;

    // 매장의 전체 메뉴 재고 목록 (재고 미설정 메뉴 포함)
    @Transactional(readOnly = true)
    public List<StockResponse> getStocks(Long storeId) {
        Map<Long, Stock> stockMap = stockRepository.findByStoreIdWithMenu(storeId)
                .stream().collect(Collectors.toMap(s -> s.getMenu().getMenuId(), s -> s));

        return menuRepository.findByIsActiveTrueOrderByCreatedAtDesc().stream()
                .map(menu -> StockResponse.of(menu, stockMap.get(menu.getMenuId())))
                .toList();
    }

    // 특정 메뉴 재고 설정 (없으면 생성, 있으면 수정)
    @Transactional
    public StockResponse setStock(Long storeId, Long menuId, StockUpdateRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다"));

        Stock stock = stockRepository.findByStore_StoreIdAndMenu_MenuId(storeId, menuId)
                .orElseGet(() -> stockRepository.save(
                        Stock.builder().store(store).menu(menu).quantity(request.resolvedQuantity()).build()));

        stock.updateQuantity(request.resolvedQuantity());
        return StockResponse.of(menu, stock);
    }
}
