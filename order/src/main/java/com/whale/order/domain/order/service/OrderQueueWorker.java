package com.whale.order.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderQueueWorker {

    private final OrderProcessingService orderProcessingService;

    // 200ms마다 대기열 확인, 있으면 처리
    @Scheduled(fixedDelay = 200)
    public void process() {
        orderProcessingService.processNext();
    }
}
