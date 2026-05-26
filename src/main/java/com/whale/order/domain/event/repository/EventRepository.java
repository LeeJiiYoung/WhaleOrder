package com.whale.order.domain.event.repository;

import com.whale.order.domain.event.entity.Event;
import com.whale.order.domain.event.entity.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatus(EventStatus status);

    // 구매 시 동시 재고 차감 직렬화용 비관적 락 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.eventId = :eventId")
    Optional<Event> findWithLock(@Param("eventId") Long eventId);

    // openAt이 도래한 SCHEDULED 이벤트 (스케줄러가 자동 오픈 처리)
    @Query("SELECT e FROM Event e WHERE e.status = 'SCHEDULED' AND e.openAt <= :now")
    List<Event> findScheduledEventsToOpen(@Param("now") LocalDateTime now);

    // 고객에게 노출할 활성 이벤트 (OPEN + SCHEDULED)
    @Query("SELECT e FROM Event e JOIN FETCH e.goods WHERE e.status IN ('OPEN', 'SCHEDULED') ORDER BY e.openAt ASC")
    List<Event> findActiveEvents();

    // 어드민 전체 목록 (상태 무관, goods JOIN FETCH — open-in-view: false 환경에서 LazyInit 방지)
    @Query("SELECT e FROM Event e JOIN FETCH e.goods ORDER BY e.openAt DESC")
    List<Event> findAllWithGoods();
}
