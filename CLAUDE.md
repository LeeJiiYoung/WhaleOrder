# 만드려고 하는것
사이렌 오더

1. 동시성 제어: 수천 명이 동시에 주문할 때 발생하는 재고 차감 문제 해결 (Redisson 분산 락 + JPA @Version 낙관적 락 이중 방어, StockLockFacade).

2. 실시간 상태 전송: 주문 접수 → 제조 중 → 완료 상태를 클라이언트에 실시간으로 푸시 (Server-Sent Events, OrderSseService).

3. 장애 내성 설계: 결제 외부 API 연동 실패 시 보상 트랜잭션(Saga 패턴) 처리.

4. React 포인트: 실시간 주문 현황 대시보드, 지도 API를 활용한 매장 위치 기반 주문 최적화.

# 문서 참조 정책

- 작업/질문에 답하기 전 관련 wiki 문서를 먼저 읽을 것: `docs/wiki/Home.md` 가 진입점.
- 답변은 항상 (1) `docs/wiki/` 내용 + (2) 실제 프로젝트 코드 를 근거로 함. 두 소스가 충돌하면 코드를 우선하고 wiki는 갱신 대상으로 표시.
- 매 세션의 주요 대화/결정은 `docs/wiki/conversations/YYYY-MM-DD.md` 에 주제 단위 섹션으로 누적 저장 (오늘 날짜 파일이 없으면 새로 만들고, 있으면 append).

# 스펙 주도 개발(SDD) 정책

이 프로젝트는 스펙 주도 개발로 진행한다. 코드를 바꾸기 전에 아래 문서를 기준으로 삼는다.

## Constitution (프로젝트 원칙)
- 모든 작업은 `docs/constitution-draft.md` 의 원칙을 따른다. 원칙을 어기는 변경이 필요하면 구현하지 말고 먼저 사람에게 확인한다.
- 특히 지킬 것: 테스트를 먼저 쓴다(VII-1), 테스트 기대값을 구현에 맞춰 바꾸지 않는다(VII-4), 외부 API 호출은 DB 트랜잭션 밖에서 한다(X).

## 기능 스펙
- 기능 단위 문서는 `specs/<번호>-<기능명>/` 아래에 있고, 번호 순서대로 읽는다.
  - `01_spec.md`: 무엇을, 왜 (요구사항 FR, 성공 기준 SC, Clarifications)
  - `02_plan.md`: 어떻게 (설계 결정과 구조)
  - `03_tasks.md`: 작업 순서
- 기능 작업을 요청받으면 해당 폴더의 세 문서를 먼저 읽고 시작한다.
- 스펙에 없는 동작은 추측해서 구현하지 않는다. 질문하거나 `[NEEDS CLARIFICATION]` 으로 남긴다.
- `03_tasks.md` 의 작업을 끝내면 체크박스를 체크한다. `[사람]` 표시 작업은 직접 하지 말고 사람에게 요청한다.
- 구현 중 설계를 바꿔야 하면 코드보다 문서(`01_spec.md`, `02_plan.md`)를 먼저 고치고 사람 승인을 받는다.

# 코딩 규칙

## 언어
- 코드 주석: 한국어
- 변수/함수명: 영어 camelCase
- 응답: 한국어

## 기술 스택
# Language/Framework: Java 21, Spring Boot 3.x

# Data Access: PostgreSQL (JSONB 활용 가능성), Querydsl (동적 쿼리 처리)

# Caching/NoSQL: Redis (장바구니, 세션, 분산 락, 랭킹 시스템)

# Messaging: Kafka (대용량 주문 스트림 처리)

# Monitoring: Prometheus & Grafana (TPS, Latency 시각화)

## 역할 분담
# 주문, 결제, 회원 정보	PostgreSQL (RDBMS)	데이터 무결성이 중요하며, 엄격한 트랜잭션(ACID) 보장이 필요함.
# 장바구니, 세션 데이터	Redis (NoSQL)	데이터 수명이 짧고, 초당 수만 건의 읽기/쓰기 성능이 필요함.
# 실시간 주문 현황 (제조 중 등)	애플리케이션 메모리 (OrderSseService의 ConcurrentHashMap, 단일 서버 한정)	주문 상태의 원본은 PostgreSQL이고, SSE 연결과 전달 대기 결과만 메모리에 둔다. 다중 서버로 확장하려면 Redis Pub/Sub 필요(미구현).