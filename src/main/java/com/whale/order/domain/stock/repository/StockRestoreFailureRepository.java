package com.whale.order.domain.stock.repository;

import com.whale.order.domain.stock.entity.StockRestoreFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockRestoreFailureRepository extends JpaRepository<StockRestoreFailure, Long> {

    List<StockRestoreFailure> findAllByOrderByFailedAtDesc();

    List<StockRestoreFailure> findAllByStoreIdOrderByFailedAtDesc(Long storeId);
}
