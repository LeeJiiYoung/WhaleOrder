package com.whale.order.global.response;

import java.util.List;

/**
 * 커서 기반 페이징 응답.
 *
 * <p>offset 페이징({@code LIMIT ? OFFSET ?})과 달리 "몇 번째부터"가 아니라 "이 값 다음부터"로 조회한다.
 * 주문처럼 계속 쌓이는 목록에서 페이지를 넘기는 사이 새 데이터가 삽입돼도
 * 같은 항목이 다음 페이지에 다시 나오거나 누락되지 않는다.
 * 깊은 페이지에서도 인덱스를 그대로 타므로 속도가 일정하다는 이점도 있다.</p>
 *
 * <p>사용법: 응답의 {@code nextCursor} 를 다음 요청의 {@code cursor} 파라미터로 그대로 전달한다.
 * {@code hasNext} 가 false 면 마지막 페이지이며, 이때 {@code nextCursor} 는 무시해도 된다.</p>
 *
 * <p>{@code nextCursor} 는 내부 구조를 감춘 불투명(opaque) 문자열이다.
 * 클라이언트는 값을 해석하지 말고 받은 그대로 되돌려주면 된다.
 * 덕분에 서버가 정렬 기준을 바꿔도 API 파라미터 시그니처는 그대로 유지된다.</p>
 *
 * @param content    현재 페이지 데이터
 * @param nextCursor 다음 페이지 요청 시 넘길 커서. 마지막 페이지면 null
 * @param hasNext    다음 페이지 존재 여부
 */
public record CursorPageResponse<T>(
        List<T> content,
        String nextCursor,
        boolean hasNext
) {
}
