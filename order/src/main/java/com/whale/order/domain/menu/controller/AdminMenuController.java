package com.whale.order.domain.menu.controller;

import com.whale.order.domain.menu.dto.*;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.service.MenuService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuService menuService;

    // 메뉴 목록 조회 (카테고리 필터 선택)
    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuResponse>>> getMenus(
            @RequestParam(required = false) MenuCategory category) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenus(category)));
    }

    // 메뉴 단건 조회 (옵션 포함)
    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenu(@PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenu(menuId)));
    }

    // 메뉴 생성 (이미지 업로드 포함)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> createMenu(
            @Valid @ModelAttribute MenuCreateRequest request) {
        MenuDetailResponse response = menuService.createMenu(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("메뉴가 등록됐습니다.", response));
    }

    // 메뉴 수정 (이미지 교체 가능)
    @PutMapping(value = "/{menuId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> updateMenu(
            @PathVariable Long menuId,
            @Valid @ModelAttribute MenuUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 수정됐습니다.", menuService.updateMenu(menuId, request)));
    }

    // 메뉴 비활성화 (소프트 삭제)
    @DeleteMapping("/{menuId}")
    public ResponseEntity<ApiResponse<Void>> deactivateMenu(@PathVariable Long menuId) {
        menuService.deactivateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 비활성화됐습니다."));
    }

    // 메뉴 활성화
    @PatchMapping("/{menuId}/activate")
    public ResponseEntity<ApiResponse<Void>> activateMenu(@PathVariable Long menuId) {
        menuService.activateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 활성화됐습니다."));
    }

    // 옵션 추가
    @PostMapping("/{menuId}/options")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> addOption(
            @PathVariable Long menuId,
            @Valid @RequestBody MenuOptionRequest request) {
        MenuOptionResponse response = menuService.addOption(menuId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("옵션이 추가됐습니다.", response));
    }

    // 옵션 수정
    @PutMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> updateOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId,
            @Valid @RequestBody MenuOptionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("옵션이 수정됐습니다.", menuService.updateOption(menuId, optionId, request)));
    }

    // 옵션 삭제
    @DeleteMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<Void>> deleteOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId) {
        menuService.deleteOption(menuId, optionId);
        return ResponseEntity.ok(ApiResponse.ok("옵션이 삭제됐습니다."));
    }
}
