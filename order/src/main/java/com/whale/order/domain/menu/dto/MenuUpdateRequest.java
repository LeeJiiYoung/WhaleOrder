package com.whale.order.domain.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 메뉴 수정 요청 (multipart/form-data).
 * imageFile이 null이면 기존 이미지를 유지한다.
 */
@Getter
@Setter
public class MenuUpdateRequest {

    @NotBlank(message = "메뉴 이름은 필수입니다.")
    private String name;

    private String description;

    @NotNull(message = "기본 가격은 필수입니다.")
    @Min(value = 0, message = "가격은 0원 이상이어야 합니다.")
    private Integer basePrice;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate saleStartDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate saleEndDate;

    // null이거나 비어 있으면 기존 이미지 유지
    private MultipartFile imageFile;
}
