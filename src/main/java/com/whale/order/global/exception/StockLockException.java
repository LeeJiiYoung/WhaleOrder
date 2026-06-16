package com.whale.order.global.exception;

// 분산 락 획득 실패 — 잠깐 후 재시도하면 성공할 수 있는 일시적 상태
// IllegalStateException(재고 부족)과 구분하여 Kafka가 재시도하도록 한다
public class StockLockException extends RuntimeException {
    public StockLockException(String message) {
        super(message);
    }
}
