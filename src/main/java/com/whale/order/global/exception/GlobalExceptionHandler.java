package com.whale.order.global.exception;

import com.whale.order.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
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

    // 요청 본문을 읽지 못함 (깨진 JSON, enum 에 없는 값, 타입 불일치 등)
    // 핸들러가 없으면 catch-all 의 "서버 오류가 발생했습니다" 로 500 이 나가, 클라이언트가 원인을 알 수 없다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[GlobalExceptionHandler] 요청 본문 파싱 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("요청 형식이 올바르지 않습니다"));
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

    // 로그인 비밀번호 불일치 — Spring Security 예외라 매핑이 없으면 catch-all 로 새서 500 이 나간다.
    // 아이디 미존재 분기(IllegalArgumentException)와 같은 400·같은 문구를 쓴다.
    // 둘을 다르게 응답하면 어떤 아이디가 존재하는지 알려주는 꼴이 된다.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(BadCredentialsException e) {
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

    // 탈퇴 불가 상태 (진행 중인 주문 보유 등) — 조건을 해소하면 재시도할 수 있다
    @ExceptionHandler(WithdrawNotAllowedException.class)
    public ResponseEntity<ApiResponse<Void>> handleWithdrawNotAllowedException(WithdrawNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    // 서비스 계층에서 거부한 권한 없는 요청 (예: OWNER·ADMIN 의 셀프 탈퇴)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(e.getMessage()));
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
