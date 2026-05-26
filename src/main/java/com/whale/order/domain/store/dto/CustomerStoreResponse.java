package com.whale.order.domain.store.dto;

import com.whale.order.domain.store.entity.Store;

import java.time.LocalTime;

public record CustomerStoreResponse(
        Long storeId,
        String name,
        String address,
        String addressDetail,
        String phone,
        LocalTime openTime,
        LocalTime closeTime,
        String status
) {
    public static CustomerStoreResponse from(Store store) {
        return new CustomerStoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddress(),
                store.getAddressDetail(),
                store.getPhone(),
                store.getOpenTime(),
                store.getCloseTime(),
                store.getStatus().name()
        );
    }
}
