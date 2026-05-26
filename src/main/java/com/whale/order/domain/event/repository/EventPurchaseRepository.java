package com.whale.order.domain.event.repository;

import com.whale.order.domain.event.entity.EventPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventPurchaseRepository extends JpaRepository<EventPurchase, Long> {

    boolean existsByEvent_EventIdAndMember_MemberId(Long eventId, Long memberId);
}
