package com.whale.order.global.exception;

/**
 * 현재 상태에서 탈퇴할 수 없을 때 발생 (예: 진행 중인 주문 보유).
 * 클라이언트가 조건을 해소한 뒤 재시도할 수 있으므로 409 Conflict 로 매핑한다.
 */
public class WithdrawNotAllowedException extends RuntimeException {
    public WithdrawNotAllowedException(String message) {
        super(message);
    }
}
