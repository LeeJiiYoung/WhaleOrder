package com.whale.order.domain.menu.controller;

import com.whale.order.domain.menu.dto.*;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.service.MenuService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "메뉴 (관리자)", description = "메뉴 등록 · 수정 · 활성화/비활성화 · 옵션 관리")
@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuService menuService;

    @Operation(summary = "메뉴 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuResponse>>> getMenus(
            @RequestParam(required = false) MenuCategory category) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenus(category)));
    }

    @Operation(summary = "메뉴 단건 조회", description = "옵션 포함")
    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenu(@PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenu(menuId)));
    }

    @Operation(summary = "메뉴 생성", description = "이미지 업로드 포함. multipart/form-data 요청")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> createMenu(
            @Valid @ModelAttribute MenuCreateRequest request) {
        MenuDetailResponse response = menuService.createMenu(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("메뉴가 등록됐습니다.", response));
    }

    @Operation(summary = "메뉴 수정", description = "이미지 교체 가능. multipart/form-data 요청")
    @PutMapping(value = "/{menuId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> updateMenu(
            @PathVariable Long menuId,
            @Valid @ModelAttribute MenuUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 수정됐습니다.", menuService.updateMenu(menuId, request)));
    }

    @Operation(summary = "메뉴 비활성화", description = "소프트 삭제 — 데이터는 유지되며 고객에게 노출되지 않음")
    @DeleteMapping("/{menuId}")
    public ResponseEntity<ApiResponse<Void>> deactivateMenu(@PathVariable Long menuId) {
        menuService.deactivateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 비활성화됐습니다."));
    }

    @Operation(summary = "메뉴 활성화", description = "비활성화된 메뉴를 다시 판매 상태로 전환")
    @PatchMapping("/{menuId}/activate")
    public ResponseEntity<ApiResponse<Void>> activateMenu(@PathVariable Long menuId) {
        menuService.activateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 활성화됐습니다."));
    }

    @Operation(summary = "옵션 추가", description = "특정 메뉴에 옵션(사이즈, 샷 추가 등)을 추가")
    @PostMapping("/{menuId}/options")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> addOption(
            @PathVariable Long menuId,
            @Valid @RequestBody MenuOptionRequest request) {
        MenuOptionResponse response = menuService.addOption(menuId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("옵션이 추가됐습니다.", response));
    }

    @Operation(summary = "옵션 수정")
    @PutMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> updateOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId,
            @Valid @RequestBody MenuOptionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("옵션이 수정됐습니다.", menuService.updateOption(menuId, optionId, request)));
    }

    @Operation(summary = "옵션 삭제")
    @DeleteMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<Void>> deleteOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId) {
        menuService.deleteOption(menuId, optionId);
        return ResponseEntity.ok(ApiResponse.ok("옵션이 삭제됐습니다."));
    }
}
