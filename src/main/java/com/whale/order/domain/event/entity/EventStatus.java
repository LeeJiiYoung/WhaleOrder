package com.whale.order.domain.event.entity;

public enum EventStatus {
    SCHEDULED,  // 오픈 전 (대기 중)
    OPEN,       // 진행 중 (구매 가능)
    CLOSED      // 종료 (품절 or 시간 종료)
}
