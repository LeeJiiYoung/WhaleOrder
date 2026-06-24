package com.whale.order.domain.store.controller;

import com.whale.order.domain.menu.dto.StoreMenuResponse;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.service.MenuService;
import com.whale.order.domain.store.dto.CustomerStoreResponse;
import com.whale.order.domain.store.service.StoreService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 고객용 매장 API.
 *
 * <p>고객이 주문 전 매장을 탐색하고 메뉴를 확인하는 흐름을 지원한다.</p>
 *
 * <pre>
 * 매장 목록 조회 → 매장 상세 조회 → 메뉴+재고 통합 조회 → 장바구니 담기 → 주문
 * </pre>
 */
@Tag(name = "매장 (고객)", description = "영업 중인 매장 목록 · 상세 조회")
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class CustomerStoreController {

    private final StoreService storeService;
    private final MenuService menuService;

    /**
     * 현재 영업 중인 매장 목록을 조회한다.
     *
     * <p>CLOSED 상태 매장은 목록에서 제외된다.</p>
     *
     * @return 영업 중인 매장 목록
     */
    @Operation(summary = "영업 중인 매장 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerStoreResponse>>> getOpenStores() {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getOpenStores()));
    }

    /**
     * 매장 상세 정보를 조회한다.
     *
     * <p>영업 상태와 무관하게 조회 가능하다.</p>
     *
     * @param storeId 조회할 매장 ID
     * @return 매장 상세 정보
     */
    @Operation(summary = "매장 상세 조회", description = "영업 중 여부와 무관하게 조회 가능")
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<CustomerStoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", storeService.getCustomerStore(storeId)));
    }

    /**
     * 매장의 판매 중 메뉴와 실시간 재고를 통합 조회한다.
     *
     * <p>비활성화된 메뉴는 포함되지 않는다.
     * {@code soldOut=true}이면 품절이므로 주문할 수 없다.
     * {@code category} 파라미터를 넘기면 해당 카테고리의 메뉴만 반환한다.</p>
     *
     * @param storeId  조회할 매장 ID
     * @param category 카테고리 필터 (미입력 시 전체)
     * @return 메뉴 목록 (옵션 · 재고 · 품절 여부 포함)
     */
    @Operation(summary = "매장별 메뉴 + 재고 통합 조회",
               description = "판매 중인 메뉴와 해당 매장의 실시간 재고를 함께 반환. soldOut=true면 품절. category 로 카테고리 필터")
    @GetMapping("/{storeId}/menus")
    public ResponseEntity<ApiResponse<List<StoreMenuResponse>>> getStoreMenus(
            @PathVariable Long storeId,
            @RequestParam(required = false) MenuCategory category) {
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", menuService.getStoreMenus(storeId, category)));
    }
}
