package com.whale.order.domain.store.dto;

import com.whale.order.domain.store.entity.Store;

import java.time.LocalTime;

public record StoreResponse(
        Long storeId,
        String name,
        String postalCode,
        String address,
        String addressDetail,
        String phone,
        LocalTime openTime,
        LocalTime closeTime,
        String status,
        String ownerName
) {
    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getPostalCode(),
                store.getAddress(),
                store.getAddressDetail(),
                store.getPhone(),
                store.getOpenTime(),
                store.getCloseTime(),
                store.getStatus().name(),
                store.getOwner().getName()
        );
    }
}
