package com.whale.order.domain.order.repository;

import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Orders, Long>, OrderRepositoryCustom {

    // 고객 - 내 주문 목록은 커서 페이징이라 Querydsl 로 구현 → OrderRepositoryCustomImpl 참조

    // 주문 상세 (items + menu 함께 로딩)
    @Query("SELECT DISTINCT o FROM Orders o JOIN FETCH o.orderItems oi JOIN FETCH oi.menu JOIN FETCH o.store JOIN FETCH o.member WHERE o.orderId = :id")
    Optional<Orders> findByIdWithDetails(@Param("id") Long id);

    // 어드민 - 전체 주문 목록 (최신순)
    @Query("SELECT o FROM Orders o JOIN FETCH o.store JOIN FETCH o.member ORDER BY o.createdAt DESC")
    List<Orders> findAllWithDetails();

    // 어드민 - 상태별 필터 (단일)
    @Query("SELECT o FROM Orders o JOIN FETCH o.store JOIN FETCH o.member WHERE o.status = :status ORDER BY o.createdAt DESC")
    List<Orders> findByStatusWithDetails(@Param("status") OrderStatus status);

    // 어드민 - 상태 복수 필터 (진행 중 탭 등)
    @Query("SELECT o FROM Orders o JOIN FETCH o.store JOIN FETCH o.member WHERE o.status IN :statuses ORDER BY o.createdAt DESC")
    List<Orders> findByStatusesWithDetails(@Param("statuses") List<OrderStatus> statuses);

    // 메트릭용 - 상태별 주문 수 카운트
    long countByStatus(OrderStatus status);

    // 회원 탈퇴 가능 여부 판정 - 접수/제조 중 주문이 하나라도 있으면 탈퇴를 막는다
    boolean existsByMember_MemberIdAndStatusIn(Long memberId, List<OrderStatus> statuses);

    // 결제 대기(AWAITING_PAYMENT) 상태로 오래 방치된 주문 조회 - PaymentSweepScheduler 전용
    List<Orders> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
