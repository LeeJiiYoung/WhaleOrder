package com.whale.order.domain.cart.controller;

import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.dto.CartResponse;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", cartService.getCart(memberId)));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CartAddRequest request) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("담겼습니다", cartService.addItem(memberId, request)));
    }

    @PatchMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey,
            @RequestParam int quantity) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("수량이 변경됐습니다", cartService.updateQuantity(memberId, itemKey, quantity)));
    }

    @DeleteMapping("/items/{itemKey}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String itemKey) {
        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("삭제됐습니다", cartService.removeItem(memberId, itemKey)));
    }

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
