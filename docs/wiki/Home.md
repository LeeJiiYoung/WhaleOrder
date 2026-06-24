# WhaleOrder Wiki

> 스타벅스 사이렌 오더를 모티브로 한 고성능 음료 주문 시스템 — 백엔드 설계/구현 문서

수천 명이 동시에 같은 메뉴를 주문해도 재고 정합성을 깨뜨리지 않고, 주문 상태를 실시간으로 사용자 화면에 푸시하며, 외부 결제 실패 시 자동으로 보상 처리하는 것을 목표로 만들었습니다.

---

## 📐 아키텍처 / 설계

- [전체 구성도](architecture/overview.md)
- [동시성 제어 — Redisson 분산 락](architecture/concurrency-control.md)
- [실시간 푸시 — SSE](architecture/realtime-sse.md)
- [Saga 보상 트랜잭션 — Kafka DLT](architecture/saga-compensation.md)
- [Kafka 이벤트 스트림](architecture/kafka-event-stream.md)
- [Redis 활용처](architecture/redis-usage.md)

## 🎯 도메인

- [Member](domains/member.md) · [Store](domains/store.md) · [Menu](domains/menu.md) · [Cart](domains/cart.md)
- [Order](domains/order.md) · [Payment](domains/payment.md) · [Stock](domains/stock.md) · [Event](domains/event.md)

## 📦 API / 스키마

- [ERD](api/erd.md)
- [REST API](api/rest-api.md)

## 🚀 운영 / 배포

- [로컬 실행](operations/local-setup.md)
- [배포 (Docker / EC2)](operations/deployment.md)
- [모니터링 (Prometheus / Grafana)](operations/monitoring.md)
- [부하 테스트 (k6)](operations/load-testing.md)
- [EC2 배포 트러블슈팅](operations/troubleshooting.md)

## 📝 학습 노트

- [notes/](notes/) — 날짜별 개념 정리 (2026-05-20 ~ 2026-06-12)

## 🤖 개발 방식

- [AI 페어 프로그래밍 워크플로](notes/ai-collaboration.md) — 문서-코드 정합성 정책 · 의사결정 로깅 · 정합성 장치가 오류를 잡은 사례

---

## 빠른 참조

| 토픽 | 핵심 파일 |
|------|-----------|
| 분산 락 | `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java` |
| 실시간 푸시 | `src/main/java/com/whale/order/domain/order/service/OrderSseService.java` |
| 보상 트랜잭션 | `src/main/java/com/whale/order/domain/order/service/OrderProcessingService.java` |
| Kafka 설정 | `src/main/java/com/whale/order/global/config/KafkaConfig.java` |
| Redisson 설정 | `src/main/java/com/whale/order/global/config/RedissonConfig.java` |
