package com.whale.order.global.exception;

/**
 * 장바구니에 이미 다른 매장의 메뉴가 담겨 있을 때 발생.
 * 한 번에 한 매장 주문만 허용하는 사이렌오더 정책상 클라이언트에 "기존 카트를 비울지" 확인을 요구한다.
 * 프런트는 HTTP 412 응답을 받으면 confirm 다이얼로그를 띄우고 동의 시 force=true 로 재요청한다.
 */
public class DifferentStoreCartException extends RuntimeException {
    public DifferentStoreCartException(String message) {
        super(message);
    }
}