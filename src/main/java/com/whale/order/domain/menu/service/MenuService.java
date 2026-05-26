package com.whale.order.domain.menu.service;

import com.whale.order.domain.menu.dto.*;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.entity.MenuOption;
import com.whale.order.domain.menu.repository.MenuOptionRepository;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.global.storage.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;
    private final ImageStorageService imageStorageService;

    @Transactional(readOnly = true)
    public List<MenuResponse> getMenus(MenuCategory category) {
        List<Menu> menus = (category != null)
                ? menuRepository.findByCategoryOrderByCreatedAtDesc(category)
                : menuRepository.findAllByOrderByCreatedAtDesc();
        return menus.stream().map(MenuResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MenuDetailResponse getMenu(Long menuId) {
        Menu menu = findMenuOrThrow(menuId);
        List<MenuOption> options = menuOptionRepository.findByMenu_MenuIdOrderByOptionGroup(menuId);
        return MenuDetailResponse.from(menu, options);
    }

    @Transactional
    public MenuDetailResponse createMenu(MenuCreateRequest request) {
        String imageUrl = uploadImageIfPresent(request.getImageFile());

        Menu menu = Menu.builder()
                .name(request.getName())
                .description(request.getDescription())
                .basePrice(request.getBasePrice())
                .category(request.getCategory())
                .imageUrl(imageUrl)
                .saleStartDate(request.getSaleStartDate())
                .saleEndDate(request.getSaleEndDate())
                .build();

        menuRepository.save(menu);
        return MenuDetailResponse.from(menu, List.of());
    }

    @Transactional
    public MenuDetailResponse updateMenu(Long menuId, MenuUpdateRequest request) {
        Menu menu = findMenuOrThrow(menuId);

        String imageUrl = menu.getImageUrl();
        if (request.getImageFile() != null && !request.getImageFile().isEmpty()) {
            imageStorageService.delete(imageUrl);
            imageUrl = imageStorageService.store(request.getImageFile());
        }

        menu.updateInfo(
                request.getName(),
                request.getDescription(),
                request.getBasePrice(),
                imageUrl,
                request.getSaleStartDate(),
                request.getSaleEndDate()
        );

        List<MenuOption> options = menuOptionRepository.findByMenu_MenuIdOrderByOptionGroup(menuId);
        return MenuDetailResponse.from(menu, options);
    }

    @Transactional
    public void deactivateMenu(Long menuId) {
        findMenuOrThrow(menuId).deactivate();
    }

    @Transactional
    public void activateMenu(Long menuId) {
        findMenuOrThrow(menuId).activate();
    }

    // 옵션 추가
    @Transactional
    public MenuOptionResponse addOption(Long menuId, MenuOptionRequest request) {
        Menu menu = findMenuOrThrow(menuId);

        MenuOption option = MenuOption.builder()
                .menu(menu)
                .optionGroup(request.optionGroup())
                .optionName(request.optionName())
                .additionalPrice(request.additionalPrice())
                .build();

        menuOptionRepository.save(option);
        return MenuOptionResponse.from(option);
    }

    // 옵션 수정
    @Transactional
    public MenuOptionResponse updateOption(Long menuId, Long optionId, MenuOptionRequest request) {
        MenuOption option = findOptionOrThrow(menuId, optionId);
        option.updateOption(request.optionName(), request.additionalPrice());
        return MenuOptionResponse.from(option);
    }

    // 옵션 삭제
    @Transactional
    public void deleteOption(Long menuId, Long optionId) {
        MenuOption option = findOptionOrThrow(menuId, optionId);
        menuOptionRepository.delete(option);
    }

    private Menu findMenuOrThrow(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다: " + menuId));
    }

    private MenuOption findOptionOrThrow(Long menuId, Long optionId) {
        MenuOption option = menuOptionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 옵션입니다: " + optionId));
        if (!option.getMenu().getMenuId().equals(menuId)) {
            throw new IllegalArgumentException("해당 메뉴의 옵션이 아닙니다.");
        }
        return option;
    }

    private String uploadImageIfPresent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return imageStorageService.store(file);
    }
}
