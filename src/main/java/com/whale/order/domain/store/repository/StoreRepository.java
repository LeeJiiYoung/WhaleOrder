package com.whale.order.domain.store.repository;

import com.whale.order.domain.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    // owner N+1 방지
    @Query("SELECT s FROM Store s JOIN FETCH s.owner ORDER BY s.storeId DESC")
    List<Store> findAllWithOwner();

    @Query("SELECT s FROM Store s JOIN FETCH s.owner WHERE s.storeId = :id")
    Optional<Store> findByIdWithOwner(@Param("id") Long id);

    // 고객용: 영업 중인 매장만 조회 (owner 불필요)
    @Query("SELECT s FROM Store s WHERE s.status = com.whale.order.domain.store.entity.StoreStatus.OPEN ORDER BY s.name ASC")
    List<Store> findAllOpenStores();
}