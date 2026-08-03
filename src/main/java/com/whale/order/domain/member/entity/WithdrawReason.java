package com.whale.order.domain.member.entity;

/**
 * 탈퇴 사유. 자유 입력이 아니라 선택형으로 받는다.
 *
 * <p>자유 텍스트를 허용하면 {@link Member#withdraw} 가 개인정보를 지우는 옆에서
 * 사유 컬럼에 연락처·이름 같은 개인정보가 새로 쌓인다. 익명화의 의미가 사라지므로 enum 으로 고정한다.
 */
public enum WithdrawReason {
    NOT_USING,        // 자주 이용하지 않음
    INCONVENIENT,     // 사용이 불편함
    PRICE,            // 가격 부담
    SERVICE_QUALITY,  // 서비스 불만
    PRIVACY,          // 개인정보 우려
    OTHER             // 기타
}
