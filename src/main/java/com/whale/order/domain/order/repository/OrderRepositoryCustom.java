package com.whale.order.domain.order.repository;

import com.whale.order.domain.order.dto.OrderCursor;
import com.whale.order.domain.order.entity.Orders;

import java.util.List;

/**
 * Querydsl 로 구현하는 주문 조회 — JPQL 로 표현하기 어려운 쿼리를 담당한다.
 */
public interface OrderRepositoryCustom {

    /**
     * 내 주문 목록을 커서 기준으로 조회한다 (최신순).
     *
     * @param cursor 직전 페이지 마지막 주문의 (생성시각, 주문ID). null 이면 첫 페이지
     * @param limit  조회할 최대 건수. 다음 페이지 존재 여부 판단을 위해 보통 pageSize + 1 을 넘긴다
     */
    List<Orders> findMyOrdersByCursor(Long memberId, OrderCursor cursor, int limit);
}
