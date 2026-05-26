package com.whale.order.domain.stock.repository;

import com.whale.order.domain.stock.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    // 주문 시 비관적 락으로 조회 — 동시 요청 직렬화
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.store.storeId = :storeId AND s.menu.menuId = :menuId")
    Optional<Stock> findWithLock(@Param("storeId") Long storeId, @Param("menuId") Long menuId);

    @Query("SELECT s FROM Stock s JOIN FETCH s.menu WHERE s.store.storeId = :storeId ORDER BY s.menu.name ASC")
    List<Stock> findByStoreIdWithMenu(@Param("storeId") Long storeId);

    Optional<Stock> findByStore_StoreIdAndMenu_MenuId(Long storeId, Long menuId);
}
