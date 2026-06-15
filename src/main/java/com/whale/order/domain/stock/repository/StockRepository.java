package com.whale.order.domain.stock.repository;

import com.whale.order.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    // Redisson 분산 락이 직렬화를 보장하므로 DB 비관적 락 불필요
    @Query("SELECT s FROM Stock s WHERE s.store.storeId = :storeId AND s.menu.menuId = :menuId")
    Optional<Stock> findByStoreAndMenu(@Param("storeId") Long storeId, @Param("menuId") Long menuId);

    @Query("SELECT s FROM Stock s JOIN FETCH s.menu WHERE s.store.storeId = :storeId ORDER BY s.menu.name ASC")
    List<Stock> findByStoreIdWithMenu(@Param("storeId") Long storeId);

    Optional<Stock> findByStore_StoreIdAndMenu_MenuId(Long storeId, Long menuId);
}
