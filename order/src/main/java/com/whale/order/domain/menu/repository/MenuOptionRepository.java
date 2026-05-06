package com.whale.order.domain.menu.repository;

import com.whale.order.domain.menu.entity.MenuOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuOptionRepository extends JpaRepository<MenuOption, Long> {

    List<MenuOption> findByMenu_MenuIdOrderByOptionGroup(Long menuId);

    void deleteByMenu_MenuId(Long menuId);
}
