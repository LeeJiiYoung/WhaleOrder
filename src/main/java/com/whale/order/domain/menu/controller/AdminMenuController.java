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

/**
 * 관리자 메뉴 관리 API.
 *
 * <p>ADMIN 전용 엔드포인트로, 메뉴 CRUD 및 옵션(사이즈·샷 등) 관리를 담당한다.
 * 메뉴 비활성화는 소프트 삭제 방식으로 처리되며, 데이터는 유지된 채 고객 화면에서만 숨겨진다.
 * 이미지가 포함된 요청은 {@code multipart/form-data}로 전송해야 한다.</p>
 */
@Tag(name = "메뉴 (관리자)", description = "메뉴 등록 · 수정 · 활성화/비활성화 · 옵션 관리")
@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuService menuService;

    /**
     * 메뉴 목록을 조회한다.
     *
     * @param category 카테고리 필터 (미입력 시 전체 조회)
     * @return 메뉴 목록
     */
    @Operation(summary = "메뉴 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuResponse>>> getMenus(
            @RequestParam(required = false) MenuCategory category) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenus(category)));
    }

    /**
     * 메뉴 단건을 옵션 목록과 함께 조회한다.
     *
     * @param menuId 조회할 메뉴 ID
     * @return 옵션이 포함된 메뉴 상세 정보
     */
    @Operation(summary = "메뉴 단건 조회", description = "옵션 포함")
    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenu(@PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getMenu(menuId)));
    }

    /**
     * 메뉴를 생성한다.
     *
     * <p>이미지 파일을 포함하므로 {@code multipart/form-data} 형식으로 요청해야 한다.</p>
     *
     * @param request 메뉴명·카테고리·가격·이미지 등 생성 정보
     * @return 생성된 메뉴 상세 정보 (HTTP 201)
     */
    @Operation(summary = "메뉴 생성", description = "이미지 업로드 포함. multipart/form-data 요청")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> createMenu(
            @Valid @ModelAttribute MenuCreateRequest request) {
        MenuDetailResponse response = menuService.createMenu(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("메뉴가 등록됐습니다.", response));
    }

    /**
     * 메뉴 정보를 수정한다.
     *
     * <p>이미지 교체가 필요한 경우 새 파일을 포함해 {@code multipart/form-data}로 요청한다.
     * 이미지를 생략하면 기존 이미지가 유지된다.</p>
     *
     * @param menuId  수정할 메뉴 ID
     * @param request 수정할 메뉴 정보
     * @return 수정된 메뉴 상세 정보
     */
    @Operation(summary = "메뉴 수정", description = "이미지 교체 가능. multipart/form-data 요청")
    @PutMapping(value = "/{menuId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuDetailResponse>> updateMenu(
            @PathVariable Long menuId,
            @Valid @ModelAttribute MenuUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 수정됐습니다.", menuService.updateMenu(menuId, request)));
    }

    /**
     * 메뉴를 비활성화한다 (소프트 삭제).
     *
     * <p>DB에서 삭제되지 않으며, 고객 화면에서만 노출이 차단된다.
     * 진행 중인 주문·이벤트 데이터의 정합성을 유지하기 위해 하드 삭제 대신 사용한다.</p>
     *
     * @param menuId 비활성화할 메뉴 ID
     */
    @Operation(summary = "메뉴 비활성화", description = "소프트 삭제 — 데이터는 유지되며 고객에게 노출되지 않음")
    @DeleteMapping("/{menuId}")
    public ResponseEntity<ApiResponse<Void>> deactivateMenu(@PathVariable Long menuId) {
        menuService.deactivateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 비활성화됐습니다."));
    }

    /**
     * 비활성화된 메뉴를 다시 판매 상태로 전환한다.
     *
     * @param menuId 활성화할 메뉴 ID
     */
    @Operation(summary = "메뉴 활성화", description = "비활성화된 메뉴를 다시 판매 상태로 전환")
    @PatchMapping("/{menuId}/activate")
    public ResponseEntity<ApiResponse<Void>> activateMenu(@PathVariable Long menuId) {
        menuService.activateMenu(menuId);
        return ResponseEntity.ok(ApiResponse.ok("메뉴가 활성화됐습니다."));
    }

    /**
     * 메뉴에 옵션을 추가한다.
     *
     * <p>옵션 그룹(SIZE·SHOT·SYRUP 등)과 옵션명, 추가 금액을 지정한다.</p>
     *
     * @param menuId  옵션을 추가할 메뉴 ID
     * @param request 옵션 그룹·옵션명·추가 금액
     * @return 생성된 옵션 정보 (HTTP 201)
     */
    @Operation(summary = "옵션 추가", description = "특정 메뉴에 옵션(사이즈, 샷 추가 등)을 추가")
    @PostMapping("/{menuId}/options")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> addOption(
            @PathVariable Long menuId,
            @Valid @RequestBody MenuOptionRequest request) {
        MenuOptionResponse response = menuService.addOption(menuId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("옵션이 추가됐습니다.", response));
    }

    /**
     * 메뉴 옵션을 수정한다.
     *
     * @param menuId   옵션이 속한 메뉴 ID
     * @param optionId 수정할 옵션 ID
     * @param request  수정할 옵션 정보
     * @return 수정된 옵션 정보
     */
    @Operation(summary = "옵션 수정")
    @PutMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<MenuOptionResponse>> updateOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId,
            @Valid @RequestBody MenuOptionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("옵션이 수정됐습니다.", menuService.updateOption(menuId, optionId, request)));
    }

    /**
     * 메뉴 옵션을 삭제한다.
     *
     * @param menuId   옵션이 속한 메뉴 ID
     * @param optionId 삭제할 옵션 ID
     */
    @Operation(summary = "옵션 삭제")
    @DeleteMapping("/{menuId}/options/{optionId}")
    public ResponseEntity<ApiResponse<Void>> deleteOption(
            @PathVariable Long menuId,
            @PathVariable Long optionId) {
        menuService.deleteOption(menuId, optionId);
        return ResponseEntity.ok(ApiResponse.ok("옵션이 삭제됐습니다."));
    }
}
