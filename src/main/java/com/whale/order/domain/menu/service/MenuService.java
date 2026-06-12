package com.whale.order.domain.menu.service;

import com.whale.order.domain.menu.dto.*;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.entity.MenuOption;
import com.whale.order.domain.menu.repository.MenuOptionRepository;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.global.storage.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;
    private final ImageStorageService imageStorageService;

    // ─── 관리자용 조회 ───────────────────────────────────────────

    @Cacheable(cacheNames = "menus", key = "#category != null ? #category.name() : 'all'")
    @Transactional(readOnly = true)
    public List<MenuResponse> getMenus(MenuCategory category) {
        List<Menu> menus = (category != null)
                ? menuRepository.findByCategoryOrderByCreatedAtDesc(category)
                : menuRepository.findAllByOrderByCreatedAtDesc();
        return menus.stream().map(MenuResponse::from).toList();
    }

    @Cacheable(cacheNames = "menu", key = "#menuId")
    @Transactional(readOnly = true)
    public MenuDetailResponse getMenu(Long menuId) {
        Menu menu = findMenuOrThrow(menuId);
        List<MenuOption> options = menuOptionRepository.findByMenu_MenuIdOrderByOptionGroup(menuId);
        return MenuDetailResponse.from(menu, options);
    }

    // ─── 고객용 조회 (메뉴 + 재고 통합, 캐시 없음) ───────────────

    @Transactional(readOnly = true)
    public List<StoreMenuResponse> getStoreMenus(Long storeId) {
        // 쿼리 1: 메뉴 + 재고 LEFT JOIN으로 한 번에 조회
        List<Object[]> rows = menuRepository.findActiveMenusWithStock(storeId);

        List<Menu> menus = rows.stream()
                .map(r -> (Menu) r[0])
                .filter(Menu::isOnSale)
                .toList();

        Map<Long, Stock> stockMap = rows.stream()
                .filter(r -> r[1] != null)
                .collect(Collectors.toMap(r -> ((Menu) r[0]).getMenuId(), r -> (Stock) r[1]));

        // 쿼리 2: 옵션 배치 조회
        List<Long> menuIds = menus.stream().map(Menu::getMenuId).toList();
        Map<Long, List<MenuOptionResponse>> optionMap = menuOptionRepository.findByMenuIds(menuIds).stream()
                .collect(Collectors.groupingBy(
                        o -> o.getMenu().getMenuId(),
                        Collectors.mapping(MenuOptionResponse::from, Collectors.toList())
                ));

        return menus.stream()
                .map(menu -> StoreMenuResponse.of(
                        menu,
                        stockMap.get(menu.getMenuId()),
                        optionMap.getOrDefault(menu.getMenuId(), List.of())
                ))
                .toList();
    }

    // ─── 쓰기 (캐시 무효화) ──────────────────────────────────────

    @Caching(evict = {
            @CacheEvict(cacheNames = "menus", allEntries = true)
    })
    @Transactional
    public MenuDetailResponse createMenu(MenuCreateRequest request) {
        String imageUrl = uploadImageIfPresent(request.getImageFile());

        Menu menu = Menu.builder()
                .name(request.getName())
                .description(request.getDescription())
                .basePrice(request.getBasePrice())
                .category(request.getCategory())
                .imageUrl(imageUrl)
                .saleStartDate(request.getSaleStartDate())
                .saleEndDate(request.getSaleEndDate())
                .build();

        menuRepository.save(menu);
        return MenuDetailResponse.from(menu, List.of());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "menus", allEntries = true),
            @CacheEvict(cacheNames = "menu", key = "#menuId")
    })
    @Transactional
    public MenuDetailResponse updateMenu(Long menuId, MenuUpdateRequest request) {
        Menu menu = findMenuOrThrow(menuId);

        String imageUrl = menu.getImageUrl();
        if (request.getImageFile() != null && !request.getImageFile().isEmpty()) {
            imageStorageService.delete(imageUrl);
            imageUrl = imageStorageService.store(request.getImageFile());
        }

        menu.updateInfo(
                request.getName(),
                request.getDescription(),
                request.getBasePrice(),
                imageUrl,
                request.getSaleStartDate(),
                request.getSaleEndDate()
        );

        List<MenuOption> options = menuOptionRepository.findByMenu_MenuIdOrderByOptionGroup(menuId);
        return MenuDetailResponse.from(menu, options);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "menus", allEntries = true),
            @CacheEvict(cacheNames = "menu", key = "#menuId")
    })
    @Transactional
    public void deactivateMenu(Long menuId) {
        findMenuOrThrow(menuId).deactivate();
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "menus", allEntries = true),
            @CacheEvict(cacheNames = "menu", key = "#menuId")
    })
    @Transactional
    public void activateMenu(Long menuId) {
        findMenuOrThrow(menuId).activate();
    }

    // ─── 옵션 관리 ────────────────────────────────────────────────

    @CacheEvict(cacheNames = "menu", key = "#menuId")
    @Transactional
    public MenuOptionResponse addOption(Long menuId, MenuOptionRequest request) {
        Menu menu = findMenuOrThrow(menuId);

        MenuOption option = MenuOption.builder()
                .menu(menu)
                .optionGroup(request.optionGroup())
                .optionName(request.optionName())
                .additionalPrice(request.additionalPrice())
                .build();

        menuOptionRepository.save(option);
        return MenuOptionResponse.from(option);
    }

    @CacheEvict(cacheNames = "menu", key = "#menuId")
    @Transactional
    public MenuOptionResponse updateOption(Long menuId, Long optionId, MenuOptionRequest request) {
        MenuOption option = findOptionOrThrow(menuId, optionId);
        option.updateOption(request.optionName(), request.additionalPrice());
        return MenuOptionResponse.from(option);
    }

    @CacheEvict(cacheNames = "menu", key = "#menuId")
    @Transactional
    public void deleteOption(Long menuId, Long optionId) {
        MenuOption option = findOptionOrThrow(menuId, optionId);
        menuOptionRepository.delete(option);
    }

    private Menu findMenuOrThrow(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다: " + menuId));
    }

    private MenuOption findOptionOrThrow(Long menuId, Long optionId) {
        MenuOption option = menuOptionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 옵션입니다: " + optionId));
        if (!option.getMenu().getMenuId().equals(menuId)) {
            throw new IllegalArgumentException("해당 메뉴의 옵션이 아닙니다.");
        }
        return option;
    }

    private String uploadImageIfPresent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return imageStorageService.store(file);
    }
}
