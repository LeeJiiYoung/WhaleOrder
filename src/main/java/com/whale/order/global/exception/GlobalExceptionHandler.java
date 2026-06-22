package com.whale.order.global.exception;

import com.whale.order.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid 검증 실패 (필수값 누락, 형식 오류 등)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("입력값이 올바르지 않습니다");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(message));
    }

    // 필수 헤더 누락
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("필수 헤더가 누락됐습니다: " + e.getHeaderName()));
    }

    // 비즈니스 로직 예외 (중복 아이디, 비밀번호 불일치 등)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    // 현재 상태에서 허용되지 않는 비즈니스 요청
    // (예: "장바구니가 비어있습니다", "영업 중이지 않은 매장입니다", "판매 중지된 메뉴입니다",
    //      "표시된 금액과 실제 금액이 다릅니다", "해당 매장에서 판매하지 않는 메뉴입니다" 등)
    // 핸들러가 없으면 catch-all 의 "서버 오류가 발생했습니다" 로 추상화돼 사용자가 실제 원인을 알 수 없음.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    // 중복 요청 (멱등성 충돌)
    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateRequestException(DuplicateRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    // 장바구니 매장 충돌 — 클라이언트에 확인 요구 (412 Precondition Failed)
    @ExceptionHandler(DifferentStoreCartException.class)
    public ResponseEntity<ApiResponse<Void>> handleDifferentStoreCartException(DifferentStoreCartException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(ApiResponse.fail(e.getMessage()));
    }

    // 결제 실패 (Mock PG 오류)
    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentFailedException(PaymentFailedException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ApiResponse.fail(e.getMessage()));
    }

    // 그 외 서버 에러
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[GlobalExceptionHandler] 처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("서버 오류가 발생했습니다"));
    }
}
