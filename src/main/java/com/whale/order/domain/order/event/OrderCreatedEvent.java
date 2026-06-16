package com.whale.order.domain.order.event;

/**
 * 주문 생성 완료 이벤트. DB 커밋 이후 Kafka 발행과 장바구니 삭제를 트리거한다.
 */
public record OrderCreatedEvent(Long orderId, Long memberId) {}
