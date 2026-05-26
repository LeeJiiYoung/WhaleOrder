package com.whale.order.domain.menu.dto;

import com.whale.order.domain.menu.entity.MenuCategory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 메뉴 생성 요청 (multipart/form-data).
 * Record 대신 클래스 사용 — @ModelAttribute 바인딩에 setter가 필요.
 */
@Getter
@Setter
public class MenuCreateRequest {

    @NotBlank(message = "메뉴 이름은 필수입니다.")
    private String name;

    private String description;

    @NotNull(message = "기본 가격은 필수입니다.")
    @Min(value = 0, message = "가격은 0원 이상이어야 합니다.")
    private Integer basePrice;

    @NotNull(message = "카테고리는 필수입니다.")
    private MenuCategory category;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate saleStartDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate saleEndDate;

    private MultipartFile imageFile;
}
