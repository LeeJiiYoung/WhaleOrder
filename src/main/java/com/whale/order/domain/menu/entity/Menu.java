package com.whale.order.domain.menu.entity;

import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 메뉴 Entity.
 * 전체 매장에 공통으로 적용되는 메뉴를 관리한다.
 * 매장별 재고는 Stock 테이블에서 별도 관리한다.
 */
@Entity
@Table(name = "menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long menuId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // 기본 가격 (옵션 추가금은 별도)
    @Column(nullable = false)
    private Integer basePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuCategory category;

    private String imageUrl;

    // 판매 시작일 - null이면 상시 판매
    private LocalDate saleStartDate;

    // 판매 종료일 - null이면 상시 판매
    private LocalDate saleEndDate;

    // false로 설정하면 앱에서 노출되지 않음 (소프트 삭제 용도)
    @Column(nullable = false)
    private Boolean isActive;

    @Builder
    public Menu(String name, String description, Integer basePrice, MenuCategory category,
                String imageUrl, LocalDate saleStartDate, LocalDate saleEndDate) {
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.category = category;
        this.imageUrl = imageUrl;
        this.saleStartDate = saleStartDate;
        this.saleEndDate = saleEndDate;
        this.isActive = true;
    }

    public void updateInfo(String name, String description, Integer basePrice,
                           String imageUrl, LocalDate saleStartDate, LocalDate saleEndDate) {
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.imageUrl = imageUrl;
        this.saleStartDate = saleStartDate;
        this.saleEndDate = saleEndDate;
    }

    // 앱 노출 여부 토글 (소프트 삭제)
    public void deactivate() {
        this.isActive = false;
    }

    public void activate() {
        this.isActive = true;
    }

    // 현재 판매 가능한 메뉴인지 확인
    public boolean isOnSale() {
        if (!isActive) return false;
        LocalDate today = LocalDate.now();
        if (saleStartDate != null && today.isBefore(saleStartDate)) return false;
        if (saleEndDate != null && today.isAfter(saleEndDate)) return false;
        return true;
    }
}
