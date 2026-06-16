package com.whale.order.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(

        @NotBlank(message = "아이디를 입력해주세요")
        @Size(min = 4, max = 20, message = "아이디는 4~20자 사이여야 합니다")
        String userId,

        @NotBlank(message = "비밀번호를 입력해주세요")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다")
        String password,

        @NotBlank(message = "이름을 입력해주세요")
        String name,

        String nickname,

        String phone,

        // 미입력 시 CUSTOMER로 가입됨. CUSTOMER/OWNER만 선택 가능 (그 외 권한은 관리자가 직접 부여)
        String role
) {
}
