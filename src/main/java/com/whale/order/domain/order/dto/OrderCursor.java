package com.whale.order.domain.order.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 주문 목록 커서 — (생성시각, 주문ID) 복합 키.
 *
 * <p>정렬 기준인 {@code createdAt} 은 값이 중복될 수 있다(같은 밀리초에 생성된 주문).
 * 그 상태로 {@code createdAt < ?} 만 쓰면 동률 구간이 통째로 누락되고,
 * {@code <=} 를 쓰면 같은 주문이 다음 페이지에 다시 나온다.
 * 그래서 유일한 {@code orderId} 를 tie-breaker 로 붙여 전순서(total order)를 만든다.</p>
 *
 * <p>클라이언트에는 Base64 로 인코딩한 불투명(opaque) 문자열로 노출한다.
 * 커서 내부 구조를 API 계약에서 감추면, 나중에 정렬 기준이 바뀌어도
 * 파라미터 시그니처를 그대로 유지할 수 있다.</p>
 */
public record OrderCursor(LocalDateTime createdAt, Long orderId) {

    private static final String DELIMITER = "|";

    /** "2026-07-27T14:03:22.481|982" → Base64 URL-safe 문자열 */
    public String encode() {
        String raw = createdAt + DELIMITER + orderId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 클라이언트가 돌려준 커서 문자열을 파싱한다.
     * 조작·손상된 값은 조용히 무시하지 않고 예외로 알린다 (잘못된 페이지를 반환하는 것보다 낫다).
     */
    public static OrderCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int idx = raw.lastIndexOf(DELIMITER);
            if (idx < 0) {
                throw new IllegalArgumentException("구분자 없음");
            }
            return new OrderCursor(
                    LocalDateTime.parse(raw.substring(0, idx)),
                    Long.parseLong(raw.substring(idx + DELIMITER.length())));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("잘못된 커서입니다. 목록을 새로고침해주세요.", e);
        }
    }
}
