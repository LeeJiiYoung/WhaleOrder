package com.whale.order.domain.menu.controller;

import com.whale.order.domain.menu.dto.CustomerMenuResponse;
import com.whale.order.domain.menu.dto.MenuOptionResponse;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuOptionRepository;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class CustomerMenuController {

    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;

    // 판매 중인 메뉴 목록 (카테고리 필터 선택)
    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerMenuResponse>>> getMenus(
            @RequestParam(required = false) MenuCategory category) {

        List<Menu> menus = (category != null)
                ? menuRepository.findByIsActiveTrueAndCategoryOrderByCreatedAtDesc(category)
                : menuRepository.findByIsActiveTrueOrderByCreatedAtDesc();

        // isOnSale() — 판매 기간 체크 (Java 레벨 필터)
        List<CustomerMenuResponse> result = menus.stream()
                .filter(Menu::isOnSale)
                .map(menu -> CustomerMenuResponse.from(menu, List.of()))
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("조회 성공", result));
    }

    // 메뉴 상세 + 옵션 목록
    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<CustomerMenuResponse>> getMenu(@PathVariable Long menuId) {
        Menu menu = menuRepository.findById(menuId)
                .filter(Menu::isOnSale)
                .orElseThrow(() -> new IllegalArgumentException("판매 중인 메뉴가 아닙니다: " + menuId));

        List<MenuOptionResponse> options = menuOptionRepository
                .findByMenu_MenuIdOrderByOptionGroup(menuId)
                .stream()
                .map(MenuOptionResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("조회 성공", CustomerMenuResponse.from(menu, options)));
    }
}
