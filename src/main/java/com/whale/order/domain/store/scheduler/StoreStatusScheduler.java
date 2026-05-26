package com.whale.order.domain.store.scheduler;

import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.entity.StoreStatus;
import com.whale.order.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoreStatusScheduler {

    private final StoreRepository storeRepository;

    // 매 분마다 영업 시간 기반으로 매장 상태 자동 갱신
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void autoUpdateStoreStatus() {
        LocalTime now = LocalTime.now();
        List<Store> stores = storeRepository.findAll();

        int opened = 0, closed = 0;
        for (Store store : stores) {
            boolean shouldBeOpen = isWithinBusinessHours(now, store.getOpenTime(), store.getCloseTime());

            if (shouldBeOpen && store.getStatus() == StoreStatus.CLOSED) {
                store.open();
                opened++;
            } else if (!shouldBeOpen && store.getStatus() == StoreStatus.OPEN) {
                store.close();
                closed++;
            }
        }

        if (opened > 0 || closed > 0) {
            log.info("[매장 상태 자동 갱신] 현재 시각: {} | 오픈: {}개, 마감: {}개", now, opened, closed);
        }
    }

    // 자정을 넘기는 영업 시간 처리 (예: 22:00 ~ 02:00)
    private boolean isWithinBusinessHours(LocalTime now, LocalTime openTime, LocalTime closeTime) {
        if (openTime.isBefore(closeTime)) {
            // 일반: 09:00 ~ 22:00
            return !now.isBefore(openTime) && now.isBefore(closeTime);
        } else {
            // 자정 넘김: openTime 이후이거나 closeTime 이전
            return !now.isBefore(openTime) || now.isBefore(closeTime);
        }
    }
}
