package com.whale.order.domain.event.dto;

import com.whale.order.domain.event.entity.Goods;

public record GoodsResponse(
        Long goodsId,
        String name,
        String description,
        Long price,
        String imageUrl,
        Long storeId,
        String storeName
) {
    public static GoodsResponse from(Goods goods) {
        return new GoodsResponse(
                goods.getGoodsId(),
                goods.getName(),
                goods.getDescription(),
                goods.getPrice(),
                goods.getImageUrl(),
                goods.getStore().getStoreId(),
                goods.getStore().getName()
        );
    }
}
