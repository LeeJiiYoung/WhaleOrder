# EC2 배포 트러블슈팅 정리

## 1. apt-get update 오류
**문제**
```
W: Some index files failed to download. They have been ignored, or old ones used instead.
```
IPv6 주소로 먼저 연결을 시도하다 실패하는 문제. EC2 인스턴스에 IPv6가 할당되지 않아 발생.

**해결**
apt가 IPv4만 사용하도록 강제 설정 (영구 적용)
```bash
echo 'Acquire::ForceIPv4 "true";' | sudo tee /etc/apt/apt.conf.d/99force-ipv4
sudo apt-get update
```

---

## 2. Docker 이미지 빌드 중 멈춤 (compileJava 단계)
**문제**
t3.micro RAM이 1GB라 Gradle 빌드 중 메모리 부족으로 멈춤.

**해결**
스왑 메모리 2GB 추가 (디스크를 메모리처럼 사용, 프리티어도 가능)
```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 확인
free -h
```

---

## 3. Private 레포지토리 클론
**문제**
Private 레포는 일반 git clone으로 접근 불가.

**해결**
GitHub Personal Access Token (PAT) 발급 후 클론
```bash
git clone https://토큰@github.com/유저명/레포지토리명.git
```
PAT 발급: GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → repo 권한 체크

---

## 4. Docker 이미지 없이 run 시도
**문제**
```
Error response from daemon: pull access denied for whaleorder, repository does not exist
```
`docker build` 없이 `docker run` 을 먼저 실행해서 발생.

**해결**
반드시 빌드 먼저 후 실행
```bash
docker build -t whaleorder .
docker run -d --name whaleorder --restart always -p 8080:8080 whaleorder
```

---

## 5. docker-compose.yml 오류 (invalid type)
**문제**
```
contains false, which is an invalid type, it should be a string, number, or a null
```
elasticsearch 환경변수에서 boolean 값을 따옴표 없이 사용해서 발생.

**해결**
`false` → `"false"` 로 따옴표 처리
```yaml
environment:
  xpack.security.enabled: "false"
```

---

## 6. 컨테이너 네트워크 문제
**문제**
```
container not attached to default bridge network
```
`docker-compose`로 띄운 컨테이너와 `docker run`으로 띄운 컨테이너가 서로 다른 네트워크에 있어서 `--link` 옵션이 작동 안 함.

**해결**
모든 컨테이너를 docker-compose로 함께 띄워서 자동으로 같은 네트워크에 묶이게 함
```bash
docker-compose -f docker-compose.prod.yml up -d
```

---

## 7. docker-compose.prod.yml 환경변수 문제
**문제**
`.env` 파일이 없어서 `${DB_HOST}` 등 환경변수가 비어있음.
(`.gitignore`에 등록되어 있어 GitHub에 올라가지 않음)

**해결**
EC2에 직접 `.env` 파일 생성
```bash
nano ~/WhaleOrder/.env
```
```
DB_HOST=siren-postgres
DB_PORT=5432
DB_NAME=whaleorder
DB_USERNAME=whale
DB_PASSWORD=whale
JWT_SECRET=your_jwt_secret_key_minimum_32_characters
```

---

## 8. volumes 선언 누락
**문제**
```
Named volume "postgres-data" is used in service "postgres" but no declaration was found in the volumes section.
```
`docker-compose.prod.yml`에 볼륨을 사용하는데 최상위 `volumes:` 선언이 없어서 발생.

**해결**
`docker-compose.prod.yml` 맨 아래에 추가
```yaml
volumes:
  postgres-data:
```

---

## 9. DB 테이블 없음 오류
**문제**
```
Schema-validation: missing table [member]
```
`application-prod.yaml`에 `ddl-auto` 설정이 없어 기본값인 `validate` 모드로 동작. 테이블이 없으면 실행 자체를 거부함.

**해결**
`application-prod.yaml`에 아래 추가
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
```
`update` 모드는 테이블이 없으면 자동 생성, 있으면 유지.

---

## 10. API 응답 없음 (보안 그룹)
**문제**
앱은 정상 실행 중인데 포스트맨에서 API 호출 시 응답 없음.

**해결**
EC2 보안 그룹 인바운드 규칙에 8080 포트 추가
- AWS 콘솔 → EC2 → 보안 그룹 → 인바운드 규칙 편집

| Type | Port | Source |
|---|---|---|
| Custom TCP | 8080 | 0.0.0.0/0 |

---

## 최종 배포 구조
```
EC2 (t3.micro)
├── Docker
│   ├── whale-backend (Spring Boot, 8080)
│   └── siren-postgres (PostgreSQL, 5432)
└── docker-compose.prod.yml로 관리
```

## 자주 쓰는 명령어
```bash
# 전체 실행
docker-compose -f docker-compose.prod.yml up -d

# 전체 중지
docker-compose -f docker-compose.prod.yml down

# 재빌드 후 실행
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml build
docker-compose -f docker-compose.prod.yml up -d

# 로그 확인
docker logs -f whale-backend

# 컨테이너 상태 확인
docker ps

# PostgreSQL 접속
docker exec -it siren-postgres psql -U whale -d whaleorder
```
