package com.whale.order.domain.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * 굿즈 생성 요청 (multipart/form-data).
 * Record 대신 클래스 사용 — @ModelAttribute 바인딩에 setter가 필요.
 */
@Getter
@Setter
public class GoodsCreateRequest {

    @NotBlank(message = "굿즈 이름을 입력해주세요")
    private String name;

    private String description;

    @NotNull(message = "가격을 입력해주세요")
    @Min(value = 0, message = "가격은 0원 이상이어야 합니다")
    private Integer price;

    @NotNull(message = "매장을 선택해주세요")
    private Long storeId;

    private MultipartFile imageFile;
}
