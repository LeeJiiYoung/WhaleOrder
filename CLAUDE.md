# 만드려고 하는것
사이렌 오더

1. 동시성 제어: 수천 명이 동시에 주문할 때 발생하는 재고 차감 문제 해결 (Redis 분산 락 또는 DB 비관적 락 활용).

2. 실시간 상태 전송: 주문 접수 → 제조 중 → 완료 상태를 클라이언트에 실시간으로 푸시 (WebSocket 또는 Server-Sent Events).

3. 장애 내성 설계: 결제 외부 API 연동 실패 시 보상 트랜잭션(Saga 패턴) 처리.

4. React 포인트: 실시간 주문 현황 대시보드, 지도 API를 활용한 매장 위치 기반 주문 최적화.

# 코딩 규칙

## 언어
- 코드 주석: 한국어
- 변수/함수명: 영어 camelCase
- 응답: 한국어

## 기술 스택
# Language/Framework: Java 21 (Virtual Threads 활용 추천), Spring Boot 3.x

# Data Access: PostgreSQL (JSONB 활용 가능성), Querydsl (동적 쿼리 처리)

# Caching/NoSQL: Redis (장바구니, 세션, 분산 락, 랭킹 시스템)

# Messaging: Kafka (대용량 주문 스트림 처리)

# Monitoring: Prometheus & Grafana (TPS, Latency 시각화)

## 역할 분담
# 주문, 결제, 회원 정보	PostgreSQL (RDBMS)	데이터 무결성이 중요하며, 엄격한 트랜잭션(ACID) 보장이 필요함.
# 장바구니, 세션 데이터	Redis (NoSQL)	데이터 수명이 짧고, 초당 수만 건의 읽기/쓰기 성능이 필요함.
# 실시간 주문 현황 (제조 중 등)	Redis / MongoDB	빠른 상태 업데이트와 정합성보다는 응답 속도가 중요함.
# 메뉴 검색, 대량 로그	Elasticsearch / NoSQL	전문(Full-text) 검색 성능 및 쓰기 중심의 대량 데이터 처리.