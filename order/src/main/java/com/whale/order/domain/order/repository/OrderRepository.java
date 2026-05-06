package com.whale.order.domain.order.repository;

import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Orders, Long> {

    // 고객 - 내 주문 목록 (최신순)
    @Query("SELECT o FROM Orders o JOIN FETCH o.store WHERE o.member.memberId = :memberId ORDER BY o.createdAt DESC")
    List<Orders> findByMemberIdWithStore(@Param("memberId") Long memberId);

    // 주문 상세 (items + menu 함께 로딩)
    @Query("SELECT DISTINCT o FROM Orders o JOIN FETCH o.orderItems oi JOIN FETCH oi.menu JOIN FETCH o.store JOIN FETCH o.member WHERE o.orderId = :id")
    Optional<Orders> findByIdWithDetails(@Param("id") Long id);

    // 어드민 - 전체 주문 목록 (최신순)
    @Query("SELECT o FROM Orders o JOIN FETCH o.store JOIN FETCH o.member ORDER BY o.createdAt DESC")
    List<Orders> findAllWithDetails();

    // 어드민 - 상태별 필터
    @Query("SELECT o FROM Orders o JOIN FETCH o.store JOIN FETCH o.member WHERE o.status = :status ORDER BY o.createdAt DESC")
    List<Orders> findByStatusWithDetails(@Param("status") OrderStatus status);
}
