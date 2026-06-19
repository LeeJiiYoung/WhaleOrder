package com.whale.order.domain.menu.entity;

import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 메뉴 옵션 Entity.
 * 사이즈, 샷 추가, 시럽, 온도 등 메뉴에 선택 가능한 옵션을 정의한다.
 * 실제 주문 시 선택된 옵션은 OrderItem.options(JSONB)에 스냅샷으로 저장된다.
 */
@Entity
@Table(name = "menu_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long menuOptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    // 옵션 그룹명 (SIZE, SHOT, SYRUP, TEMPERATURE)
    @Column(nullable = false, length = 50)
    private String optionGroup;

    // 옵션 값 (TALL, GRANDE, VENTI / 1샷, 2샷 / 바닐라, 헤이즐넛 등)
    @Column(nullable = false, length = 50)
    private String optionName;

    // 기본 가격 대비 추가 금액 (0원이면 무료 옵션)
    @Column(nullable = false)
    private Integer additionalPrice;

    // 필수 선택 여부. 같은 menu_id + option_group 의 모든 행은 동일한 값을 유지한다.
    @Column(nullable = false)
    private Boolean isRequired;

    @Builder
    public MenuOption(Menu menu, String optionGroup, String optionName, Integer additionalPrice, Boolean isRequired) {
        this.menu = menu;
        this.optionGroup = optionGroup;
        this.optionName = optionName;
        this.additionalPrice = additionalPrice;
        this.isRequired = isRequired != null ? isRequired : false;
    }

    public void updateOption(String optionName, Integer additionalPrice, Boolean isRequired) {
        this.optionName = optionName;
        this.additionalPrice = additionalPrice;
        this.isRequired = isRequired != null ? isRequired : false;
    }
}