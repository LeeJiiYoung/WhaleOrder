package com.whale.order.domain.cart.controller;

import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * 장바구니 API 컨트롤러.
 * Redis에 저장되며 TTL 24시간이 지나면 자동 만료된다.
 */
@Tag(name = "장바구니", description = "Redis 기반 장바구니 · TTL 24시간")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * 로그인한 회원의 장바구니를 조회한다.
     */
    @Operation(summary = "장바구니 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", cartService.getCart(memberId)));
    }

    /**
     * 장바구니에 메뉴를 담는다.
     * force=true 면 기존 카트가 다른 매장 메뉴를 가지고 있어도 비우고 새 메뉴를 담는다.
     * force=false (기본) 면 매장 충돌 시 412 Precondition Failed 로 클라이언트에 확인 요구.
     */
    @Operation(summary = "메뉴 담기")
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CartAddRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("담겼습니다", cartService.addItem(memberId, request, force)));
    }

    /**
     * 장바구니에 담긴 항목의 수량을 변경한다.
     */
    @Operation(summary = "수량 변경")
    @PatchMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey,
            @RequestParam int quantity) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("수량이 변경됐습니다", cartService.updateQuantity(memberId, itemKey, quantity)));
    }

    /**
     * 장바구니에서 항목 하나를 삭제한다.
     */
    @Operation(summary = "메뉴 삭제")
    @DeleteMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("삭제됐습니다", cartService.removeItem(memberId, itemKey)));
    }

    /**
     * 장바구니를 전부 비운다.
     */
    @Operation(summary = "장바구니 비우기")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(
            @AuthenticationPrincipal UserDetails userDetails) {
        cartService.clearCart(memberId(userDetails));
        return ResponseEntity.ok(ApiResponse.ok("장바구니를 비웠습니다"));
    }

    /**
     * 인증된 사용자의 username(JWT subject)을 회원 ID로 변환한다.
     */
    private Long memberId(UserDetails userDetails) {
        return Long.parseLong(userDetails.getUsername());
    }
}
