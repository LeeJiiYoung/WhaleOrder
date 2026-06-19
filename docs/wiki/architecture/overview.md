# 시스템 구성도

> WhaleOrder 백엔드 전체 구성과 컴포넌트별 책임

![architecture](../assets/architecture.png)

## 컴포넌트

| 계층 | 기술 | 역할 |
|------|------|------|
| API | Spring Boot 3 / Java 21 | REST · SSE 엔드포인트 |
| RDBMS | PostgreSQL | 주문 · 결제 · 회원 등 정합성 데이터 |
| Cache / Lock | Redis + Redisson | 장바구니, 리프레시 토큰, 대기열, 분산 락, 메뉴 캐시 |
| Messaging | Kafka | 주문 처리 비동기화, DLT 기반 보상 트리거 |
| Monitoring | Prometheus + Grafana + Micrometer | 메트릭 수집/시각화 |
| 부하 테스트 | k6 | 일반 부하 + 동시성 검증 |

## 주요 흐름 — 선결제

```
[Client]
   │ ① POST /api/payments
   ▼
[PaymentService.pay()]  @Transactional
   │ ② 장바구니 + 매장 OPEN + 메뉴 isOnSale + Stock 검증
   │ ③ SHA-256 멱등성 키 (중복 결제 방어)
   │ ④ Orders + Payment(PENDING) 저장
   │ ⑤ Mock 결제 판정 (90% 성공)
   │ ⑥ 성공: Payment SUCCESS + OrderCreatedEvent publish
   │ ⑦ 실패: 전체 롤백 + PaymentFailedException
   ▼
━ TX commit ━
   │
[OrderEventListener]  @TransactionalEventListener(AFTER_COMMIT)
   │ ⑧ Kafka publish (order-created) + 장바구니 비우기
   ▼
[Kafka: order-created]
   │ ⑨ Consumer 처리
   ▼
[OrderProcessingService]
   │ ⑩ 재고 차감 (StockLockFacade — 분산락) + SSE 푸시
   │ ⑪ 차감 실패 시 부분 복구 + OrderCancelService.cancelOrder()
   │ ⑫ 3회 재시도 실패 시 DLT → compensate() → 주문/결제 동시 취소
```

## 관련 문서

- [동시성 제어](concurrency-control.md)
- [실시간 푸시](realtime-sse.md)
- [Saga 보상 트랜잭션](saga-compensation.md)
- [Kafka 이벤트 스트림](kafka-event-stream.md)
- [Redis 활용처](redis-usage.md)
