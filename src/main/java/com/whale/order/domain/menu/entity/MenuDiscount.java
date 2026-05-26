package com.whale.order.domain.menu.entity;

import com.whale.order.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 메뉴 할인 Entity.
 * 특정 기간 동안 메뉴에 고정 금액 할인을 적용한다.
 * 할인가 = Menu.basePrice - MenuDiscount.discountAmount
 */
@Entity
@Table(name = "menu_discount")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuDiscount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long discountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    // 할인 금액 (원 단위 고정 할인)
    @Column(nullable = false)
    private Integer discountAmount;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Builder
    public MenuDiscount(Menu menu, Integer discountAmount, LocalDate startDate, LocalDate endDate) {
        validateDate(startDate, endDate);
        this.menu = menu;
        this.discountAmount = discountAmount;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public void updateDiscount(Integer discountAmount, LocalDate startDate, LocalDate endDate) {
        validateDate(startDate, endDate);
        this.discountAmount = discountAmount;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // 현재 날짜 기준으로 할인 적용 중인지 확인
    public boolean isActive() {
        LocalDate today = LocalDate.now();
        return !today.isBefore(startDate) && !today.isAfter(endDate);
    }

    private void validateDate(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("할인 종료일은 시작일보다 빠를 수 없습니다.");
        }
    }
}
