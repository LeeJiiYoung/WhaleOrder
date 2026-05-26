package com.whale.order.domain.menu.repository;

import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByOrderByCreatedAtDesc();

    List<Menu> findByCategoryOrderByCreatedAtDesc(MenuCategory category);

    List<Menu> findByIsActiveOrderByCreatedAtDesc(Boolean isActive);

    // 고객용: 활성 메뉴 + 카테고리 필터
    List<Menu> findByIsActiveTrueOrderByCreatedAtDesc();

    List<Menu> findByIsActiveTrueAndCategoryOrderByCreatedAtDesc(MenuCategory category);
}
