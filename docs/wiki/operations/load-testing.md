# 부하 테스트 — k6

> 일반 부하 시나리오 + 동시성 검증 시나리오 2종

**관련 파일**
- `k6/order-load-test.js`
- `k6/stock-concurrency-test.js`

## 시나리오 1 — 일반 부하 (`order-load-test.js`)

| 단계 | 시간 | VU | 목적 |
|------|------|----|------|
| ramp-up | 30s | → 10명 | 워밍업 |
| 유지 | 1m | 10명 | 베이스라인 |
| ramp-up | 30s | → 50명 | 부하 증가 |
| 유지 | 1m | 50명 | 피크 부하 |

**임계값**: p95 응답시간 < 2초, 에러율 < 5%

## 시나리오 2 — 동시성 (`stock-concurrency-test.js`)

```
재고: 10개
VU: 20명 동시 주문
기대: 성공 정확히 10건, 초과 주문 0건
```

`StockLockFacade` 분산 락이 제 역할을 하는지 검증.

## 실행

```bash
k6 run k6/order-load-test.js
k6 run k6/stock-concurrency-test.js
```

## 메트릭 연동

`k6` 는 Prometheus remote-write 또는 statsd로 메트릭 전송 가능 → Grafana 대시보드와 통합 시각화 가능 (`monitoring/prometheus.yml` 참조).

## 관련 문서

- [동시성 제어](../architecture/concurrency-control.md)
- [모니터링](monitoring.md)
