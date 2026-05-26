package com.whale.order.domain.event.repository;

import com.whale.order.domain.event.entity.Goods;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GoodsRepository extends JpaRepository<Goods, Long> {

    @Query("SELECT g FROM Goods g JOIN FETCH g.store ORDER BY g.createdAt DESC")
    List<Goods> findAllWithStore();
}
