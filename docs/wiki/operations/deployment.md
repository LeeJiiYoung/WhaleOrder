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

- [트러블슈팅 (EC2 10가지 사례)](troubleshooting.md)
- [모니터링](monitoring.md)
