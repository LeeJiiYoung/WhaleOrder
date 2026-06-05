package com.whale.order.domain.menu.controller;

import com.whale.order.domain.menu.dto.CustomerMenuResponse;
import com.whale.order.domain.menu.dto.MenuOptionResponse;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuOptionRepository;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "메뉴 (고객)", description = "판매 중인 메뉴 목록 · 상세 조회")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class CustomerMenuController {

    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;

    @Operation(summary = "메뉴 목록 조회", description = "판매 중인 메뉴만 반환. 카테고리 필터 선택 가능")
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

    @Operation(summary = "메뉴 상세 조회", description = "옵션 목록 포함")
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
