package com.whale.order.domain.menu.repository;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByOrderByCreatedAtDesc();

    List<Menu> findByCategoryOrderByCreatedAtDesc(MenuCategory category);

    List<Menu> findByIsActiveOrderByCreatedAtDesc(Boolean isActive);

    // 고객용: 활성 메뉴 + 카테고리 필터
    List<Menu> findByIsActiveTrueOrderByCreatedAtDesc();

    List<Menu> findByIsActiveTrueAndCategoryOrderByCreatedAtDesc(MenuCategory category);

    // 매장별 메뉴 + 재고 한 번에 조회 (메뉴 기준 LEFT JOIN — 재고 없는 메뉴도 포함)
    @Query("""
            SELECT m, s FROM Menu m
            LEFT JOIN Stock s ON s.menu = m AND s.store.storeId = :storeId
            WHERE m.isActive = true
            ORDER BY m.createdAt DESC
            """)
    List<Object[]> findActiveMenusWithStock(@Param("storeId") Long storeId);
}
