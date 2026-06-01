할것
우선순위	항목	이유
1	Kafka DLQ + 재시도 정책	현재 실패 시 무한 재시도 가능성 — 장애 내성의 미완성 고리
2	Virtual Thread 설정	CLAUDE.md에 명시된 Java 21 핵심 기능, 설정 코드 없음
3	Prometheus 커스텀 비즈니스 메트릭	TPS·결제 성공률·재고 부족 횟수 등 도메인 메트릭이 없음
4	Elasticsearch 메뉴 검색 연동	docker-compose에는 있지만 Spring 연동 코드 없음
5	Refresh Token 재발급 API	인증 흐름 완성 (30분 만료 후 처리 없음)
6	통합 테스트 (결제 → 주문 → SSE 플로우)	현재 재고 동시성 테스트만 있음
