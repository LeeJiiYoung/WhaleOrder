# 로컬 실행

> 개발 환경에서 WhaleOrder를 띄우는 방법

## 사전 요구

- Java 21
- Docker / Docker Compose
- (선택) Node.js — 프론트 개발 시

## 실행 절차

### 1. 인프라 컨테이너 기동

```bash
docker-compose up -d
```

`docker-compose.yml` 이 띄우는 것:
- PostgreSQL
- Redis
- Kafka + Zookeeper
- Prometheus
- Grafana

### 2. 백엔드 실행

```bash
./gradlew bootRun
```

기본 포트: 8080

### 3. 프론트엔드 (선택)

```bash
cd frontend
npm install
npm run dev
```

### 4. 모니터링 UI

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (초기 admin/admin)

## 환경변수 / 설정

- 백엔드 설정: `src/main/resources/application.yaml`
- Docker 설정: `docker-compose.yml`

## 관련 문서

- [배포](deployment.md)
- [모니터링](monitoring.md)
- [트러블슈팅](troubleshooting.md)
