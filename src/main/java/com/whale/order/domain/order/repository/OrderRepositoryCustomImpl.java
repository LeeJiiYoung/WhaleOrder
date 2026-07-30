package com.whale.order.domain.order.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.whale.order.domain.order.dto.OrderCursor;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.entity.QOrders;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Orders> findMyOrdersByCursor(Long memberId, OrderCursor cursor, int limit) {
        QOrders order = QOrders.orders;

        return queryFactory
                .selectFrom(order)
                // ToOne(store)만 fetch join. 컬렉션(orderItems)을 조인하면 결과 행이 자식 수만큼 늘어나
                // limit 이 "주문 N건"이 아니라 "조인 결과 N행"을 잘라버려, Hibernate 가 전체를
                // 메모리로 올려 페이징한다(HHH000104 → 대용량 시 OOM).
                // orderItems·menu 는 지연 로딩 + default_batch_fetch_size(100) 로 IN 절 일괄 조회된다.
                .join(order.store).fetchJoin()
                .where(order.member.memberId.eq(memberId), cursorCondition(order, cursor))
                .orderBy(order.createdAt.desc(), order.orderId.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 커서 조건 — PostgreSQL 행 값 비교 {@code (created_at, order_id) < (?, ?)}.
     *
     * <p>JPQL 로는 튜플 비교를 표현할 수 없어 {@code A < ? OR (A = ? AND B < ?)} 로 풀어써야 하는데,
     * 그 OR 형태는 옵티마이저가 복합 인덱스 range scan 으로 풀지 못하고 필터로 처리하는 경우가 있다.
     * 행 값 비교는 {@code (member_id, created_at, order_id)} 인덱스를 그대로 타므로
     * 페이지가 깊어져도 조회 비용이 일정하다.</p>
     *
     * <p>첫 페이지(cursor == null)에는 null 을 반환한다 — Querydsl 이 where 절에서 자동으로 제외한다.</p>
     *
     * <p>주의: 행 값 비교는 PostgreSQL·MySQL 등이 지원하는 SQL 표준 문법이지만 모든 DB 가 지원하진 않는다.
     * 이식성이 필요하면 아래로 대체할 수 있다 (인덱스 활용도는 떨어진다).
     * <pre>
     * order.createdAt.lt(cursor.createdAt())
     *      .or(order.createdAt.eq(cursor.createdAt()).and(order.orderId.lt(cursor.orderId())))
     * </pre>
     */
    private BooleanExpression cursorCondition(QOrders order, OrderCursor cursor) {
        if (cursor == null) return null;

        return Expressions.booleanTemplate("({0}, {1}) < ({2}, {3})",
                order.createdAt, order.orderId, cursor.createdAt(), cursor.orderId());
    }
}
