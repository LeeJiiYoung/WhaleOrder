package com.whale.order.domain.event.dto;

import com.whale.order.domain.event.entity.Event;

import java.time.LocalDateTime;

public record EventResponse(
        Long eventId,
        String name,
        String goodsName,
        String goodsDescription,
        Integer goodsPrice,
        String goodsImageUrl,
        LocalDateTime openAt,
        Integer capacity,
        Integer remainingCapacity,
        String status
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getEventId(),
                event.getName(),
                event.getGoods().getName(),
                event.getGoods().getDescription(),
                event.getGoods().getPrice(),
                event.getGoods().getImageUrl(),
                event.getOpenAt(),
                event.getCapacity(),
                event.getRemainingCapacity(),
                event.getStatus().name()
        );
    }
}
