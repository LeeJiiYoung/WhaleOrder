package com.whale.order.domain.menu.repository;

import com.whale.order.domain.menu.entity.MenuOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuOptionRepository extends JpaRepository<MenuOption, Long> {

    List<MenuOption> findByMenu_MenuIdOrderByOptionGroup(Long menuId);

    // 여러 메뉴의 옵션을 한 번에 조회 (N+1 방지)
    @Query("SELECT o FROM MenuOption o WHERE o.menu.menuId IN :menuIds ORDER BY o.menu.menuId, o.optionGroup")
    List<MenuOption> findByMenuIds(@Param("menuIds") List<Long> menuIds);

    void deleteByMenu_MenuId(Long menuId);
}
