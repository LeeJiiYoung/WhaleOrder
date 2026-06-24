# 모니터링 — Prometheus / Grafana / Micrometer

> 처리량, 응답시간, 실패율을 실시간 추적

**관련 파일**
- `monitoring/prometheus.yml`
- `monitoring/grafana/provisioning/` (datasource, dashboards)
- `src/main/resources/application.yaml` (actuator/prometheus 활성화)
- `src/main/java/com/whale/order/domain/order/service/OrderProcessingService.java` (커스텀 메트릭)

## 스택

```
Spring Boot (Micrometer) ──/actuator/prometheus──▶ Prometheus ──▶ Grafana
                                                       ▲
                                   k6 ──remote-write──┘
```

- Spring Actuator + Micrometer 가 메트릭 수출
- Prometheus 가 15초 간격 스크랩
- Grafana 가 시각화 (자동 프로비저닝)

## 커스텀 메트릭 (OrderProcessingService)

| 이름 | 타입 | 태그 | 의미 |
|------|------|------|------|
| `order.processed` | Counter | `result=success\|failure` | 주문 처리 결과 횟수 |
| `order.stock.shortage` | Counter | — | 재고 부족 발생 횟수 |
| `order.processing.time` | Timer | `result=success\|failure` | 주문 처리 소요 시간 |

코드 위치: `OrderProcessingService.initMetrics()` (라인 41~54)

## Grafana 대시보드 패널

`monitoring/grafana/provisioning/dashboards/spring-boot.json` — TPS, p95 응답시간, 에러율, 큐 크기 등 핵심 지표 패널 포함.

## 접근

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- Actuator: http://localhost:8080/actuator/prometheus

## 관련 문서

- [부하 테스트 (k6)](load-testing.md) — k6 결과도 Prometheus로 수집됨
- 학습 노트: [캐싱·모니터링·로깅 (2026-06-12)](../notes/개념정리_20260612.md)
