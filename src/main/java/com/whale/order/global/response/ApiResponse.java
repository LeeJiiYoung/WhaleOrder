package com.whale.order.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 API 응답 래퍼.
 * 모든 API 응답은 이 형식으로 반환된다.
 *
 * 성공: {"success": true, "message": "...", "data": {...}}
 * 실패: {"success": false, "message": "...", "data": null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // data가 null이면 응답 JSON에서 제외
public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {

    // 데이터 있는 성공 응답
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "성공", data);
    }

    // 데이터 없는 성공 응답 (생성, 삭제 등)
    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null);
    }

    // 메시지와 데이터 모두 있는 성공 응답
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    // 실패 응답
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
