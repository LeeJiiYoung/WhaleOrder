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

@Tag(name = "장바구니", description = "Redis 기반 장바구니 · TTL 24시간")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "장바구니 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", cartService.getCart(memberId)));
    }

    @Operation(summary = "메뉴 담기")
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CartAddRequest request) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("담겼습니다", cartService.addItem(memberId, request)));
    }

    @Operation(summary = "수량 변경")
    @PatchMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey,
            @RequestParam int quantity) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("수량이 변경됐습니다", cartService.updateQuantity(memberId, itemKey, quantity)));
    }

    @Operation(summary = "메뉴 삭제")
    @DeleteMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("삭제됐습니다", cartService.removeItem(memberId, itemKey)));
    }

    @Operation(summary = "장바구니 비우기")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(
            @AuthenticationPrincipal UserDetails userDetails) {
        cartService.clearCart(memberId(userDetails));
        return ResponseEntity.ok(ApiResponse.ok("장바구니를 비웠습니다"));
    }

    private Long memberId(UserDetails userDetails) {
        return Long.parseLong(userDetails.getUsername());
    }
}
