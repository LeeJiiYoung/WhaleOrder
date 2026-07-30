# 배포 — Docker / EC2

> 운영용 Docker 빌드와 EC2 배포 가이드

**관련 파일**
- `Dockerfile`
- `docker-compose.prod.yml`
- `docker-compose.yml`

## Docker 이미지 빌드

```bash
./gradlew bootJar
docker build -t whaleorder:latest .
```

`Dockerfile` 은 JRE 21 베이스로 jar 실행. 빌드 산출물은 `build/libs/*.jar`.

## 운영 컴포즈 실행

```bash
docker-compose -f docker-compose.prod.yml up -d
```

dev/prod 차이:

| 항목 | dev | prod |
|------|-----|------|
| 포트 노출 | 모든 인프라 외부 노출 | Spring Boot 만 외부 노출 |
| 로깅 | 콘솔 | 파일 + 콘솔 |
| Kafka replicas | 1 | 운영 환경에 맞춰 조정 |

## 프리티어(t3.micro, RAM 1GB) 경량 배포

t3.micro는 RAM이 1GB뿐이라 dev용 8개 서비스(특히 Kafka ~500MB~1GB, 모니터링 스택)를 다 올리면
OOM으로 인스턴스가 다운된다. 그래서 배포는 **필수 4개(backend·frontend·postgres·redis)만** 올린다.

### 1. Kafka 없이 동작하는 원리

- `application-prod.yaml` 이 `KafkaAutoConfiguration` 제외 + `kafka.enabled=false` 로 Kafka 관련 빈을 전부 끈다.
- `OrderEventListener` 가 Kafka Producer 부재를 감지하면 `orderProcessingService.process()` 를 **동기 호출**하는 폴백으로 전환된다.
- 결과: **SSE 실시간 푸시·재고차감·재고부족 보상은 그대로 동작**한다. 데모 트래픽 수준에선 체감 차이 없음.

**포기하는 것 — 아래 두 가지는 실제로 사라진다.**

| 항목 | Kafka OFF일 때 |
|------|---------------|
| 요청 스레드 격리 | `process()` 가 요청 스레드에서 돌아 분산 락 대기(`tryLock` 최대 5초)가 응답 시간에 포함된다. `max-threads: 50` 이라 인기 메뉴에 몰리면 **주문과 무관한 API까지 함께 마비**된다. |
| 락 타임아웃 자동 복구 | `StockLockException` 은 `OrderProcessingService` 가 rethrow 하는데, Kafka 모드에선 재시도 → DLT → `compensate()` 로 이어진다. 동기 모드엔 그 경로가 없어 `OrderEventListener` 가 예외를 삼키고 **주문이 PENDING으로 방치**된다 (SSE 통지도 없음, 수동 재처리 필요). |

재고 부족(`IllegalStateException`)은 `handleFailure()` 안에서 재고 복구 + 주문 취소 + SSE FAILED 까지 처리하므로 동기 모드에서도 정상 동작한다. 즉 **"Saga 보상 전체"가 아니라 "DLT 경유 보상"만 빠진다.**

자세한 비교: [Kafka 이벤트 스트림 — 동기 폴백 모드](../architecture/kafka-event-stream.md#동기-폴백-모드-kafkaenabled-false)

### 2. 스왑 2GB (필수)

1GB 환경에선 스왑이 없으면 피크 때 OOM Killer가 컨테이너를 죽인다. EC2에서 1회 설정:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # 재부팅 후에도 유지
free -h   # Swap 2.0Gi 확인
```

### 3. 배포 명령

```bash
# 평소 배포 (경량, 4개 서비스)
docker compose -f docker-compose.prod.yml up -d

# 지표 확인이 필요할 때만 모니터링 오버레이를 얹음 (t3.micro에선 잠깐만)
docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml up -d

# 로그 확인 (Grafana 없이)
docker compose -f docker-compose.prod.yml logs -f backend
```

### 4. 메모리 관련 설정 요약

| 설정 | 위치 | 목적 |
|------|------|------|
| `JAVA_TOOL_OPTIONS=-Xmx400m` | `docker-compose.prod.yml` backend | JVM 힙 상한 |
| `tomcat.threads.max=50` | `application-prod.yaml` | 스레드 스택 누적 절감 |
| `hikari.maximum-pool-size=10` | `application-prod.yaml` | DB 커넥션당 메모리 절감 |
| 스왑 2GB | EC2 호스트 | OOM 방지 안전망 |

## EC2 배포 흐름

```
GitHub Actions
   └─ build & test
       └─ docker build & push (ECR)
           └─ EC2 ssh deploy
               └─ docker-compose pull + up -d
```

자세한 흐름과 트러블슈팅 사례는 [트러블슈팅](troubleshooting.md) 참조.

## CI/CD

GitHub Actions 워크플로 (`.github/workflows/`) — README.md § CI/CD 섹션 참고.

## 관련 문서

- [트러블슈팅 (EC2 사례 모음)](troubleshooting.md)
- [모니터링](monitoring.md)
