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
EC2에 직접 `.env` 파일 생성 (레포 클론 경로는 `~/whale-order` — 배포 워크플로 기준)
```bash
nano ~/whale-order/.env
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

---

> ### 📌 아래 11~20은 2026-07-20 작업 기록
> DuckDNS + Caddy로 HTTPS 도메인 전환, CI/CD 안정화, 도메인 전환에 따라 드러난 운영 버그 대응.

## 11. HTTPS 도메인 연결 (DuckDNS + Caddy)
**문제**
`http://<EC2-IP>:3000` 형태로만 접속 가능하고 HTTPS·도메인이 없음.

**해결**
- 무료 서브도메인(DuckDNS)에서 `whaleorder.duckdns.org` 생성 → current ip 를 EC2 IP로 설정.
- `Caddyfile` + `docker-compose.prod.yml`에 Caddy(리버스 프록시) 추가. Let's Encrypt 인증서 자동 발급.
  흐름: `브라우저 → Caddy(443) → frontend nginx(:80) → (/api) backend:8080`
- **인증서 발급 성공 조건 3가지** (하나라도 빠지면 `docker logs whale-caddy`에 발급 에러 반복):
  1. DuckDNS A레코드가 이 EC2를 가리킴 (`nslookup whaleorder.duckdns.org`)
  2. 보안그룹 인바운드 **80, 443** 개방 (80은 발급 챌린지에 필요)
  3. Caddyfile의 도메인이 실제 소유 도메인과 일치
- 재기동마다 재발급하지 않도록 `caddy-data` 볼륨으로 인증서 보존 (Let's Encrypt 주간 발급 한도 주의).

---

## 12. 배포 시 git pull 충돌 (서버 로컬 수정)
**문제**
```
error: Your local changes to the following files would be overwritten by merge:
	docker-compose.prod.yml
```
EC2에서 파일을 손으로 수정(예: JWT_SECRET 하드코딩)해 커밋 안 된 변경이 남아, 배포 스크립트의 `git pull`이 거부됨.

**해결**
서버는 순수 배포 대상이어야 함. deploy.yml을 `git pull` → **강제 동기화**로 변경:
```bash
git fetch origin main && git reset --hard origin/main
```
`.env`는 gitignore라 `reset`에 지워지지 않음. 수동 해결이 필요하면 `git diff`로 확인 후 `git reset --hard origin/main`.

---

## 13. 컨테이너 이름 충돌 (Conflict)
**문제**
```
Conflict. The container name "/5d3c1174f774_whale-backend" is already in use ...
```
`--force-recreate`가 재생성 중 임시 리네임(`<hash>_이름`)하는데, t3.micro OOM 등으로 중단되면 그 좀비 컨테이너가 남아 다음 배포와 충돌.

**해결**
`--force-recreate` 의존을 버리고, up 전에 **명시적으로 대상 컨테이너 제거**:
```bash
docker ps -aq -f name=whale-backend -f name=whale-frontend | xargs -r docker rm -f
docker-compose -f docker-compose.prod.yml up -d --remove-orphans
```
즉시 복구는 에러의 컨테이너 ID를 `docker rm -f <id>`.

---

## 14. push했는데 docker-compose가 자동 반영 안 됨
**문제**
Actions는 도는데 배포 결과가 적용 안 됨.

**해결**
deploy 스크립트에 `set -e`가 있으면, 앞 단계(예: `git pull`)가 실패하는 순간 **중단되어 `docker-compose up`까지 도달하지 못함**. Actions 탭에서 deploy 단계가 빨간 X인지 확인 → 대개 [12번](#12-배포-시-git-pull-충돌-서버-로컬-수정) 충돌이 원인. fetch+reset로 해결하면 이후 자동 반영됨.

---

## 15. 기존 http://IP:3000 접속 불가
**문제**
Caddy 도입 후 `http://<EC2-IP>:3000`으로 안 들어가짐.

**해결**
정상. 프론트의 호스트 포트 노출(`3000:80`)을 제거하고 `expose`로 바꿔 **Caddy를 유일한 진입점**으로 만들었기 때문. 이제 `https://whaleorder.duckdns.org`로 접속. 전환 기간에 임시로 3000이 필요하면 compose의 `ports` 주석을 잠깐 해제.

---

## 16. 카카오 로그인 실패 (리버스 프록시 뒤 OAuth)
**문제**
도메인 전환 후 카카오 로그인이 `redirect_uri mismatch` 등으로 실패. 프론트 버튼이 `http://localhost:8080/...`로 하드코딩돼 있기도 함.

**해결** (여러 지점 동시 수정)
- **프론트 버튼**: `http://localhost:8080/oauth2/authorization/kakao` → 상대경로 `/oauth2/authorization/kakao`.
- **nginx**: `/api`가 아닌 `/oauth2/authorization/`·`/login/oauth2/`도 백엔드로 프록시 추가 (안 하면 SPA로 흡수돼 백엔드 미도달). `/oauth2/callback`은 SPA 라우트라 그대로 둠.
- **`{baseUrl}` 복원**: 프록시(Caddy→nginx→backend) 뒤에서는 `{baseUrl}`이 내부 http로 계산됨 → `application-prod.yaml`에 `server.forward-headers-strategy: framework` + nginx가 `X-Forwarded-Proto/Host` 전달.
- **프론트 리다이렉트**: `.env`의 `OAUTH2_REDIRECT_URI=https://whaleorder.duckdns.org/oauth2/callback`, compose가 backend로 전달.
- **카카오 콘솔**: Redirect URI `https://whaleorder.duckdns.org/login/oauth2/code/kakao` + Web 플랫폼 도메인 등록.

---

## 17. 카카오맵 appkey=undefined
**문제**
```
https://dapi.kakao.com/v2/maps/sdk.js?appkey=undefined&autoload=false
```
서버 `.env`에 `VITE_KAKAO_MAP_KEY`를 넣었는데도 지도 안 뜸.

**해결**
Vite 환경변수(`import.meta.env.VITE_*`)는 **런타임이 아니라 빌드 시점에 번들에 박힘**. 정적 번들은 서버 `.env`를 못 읽음(백엔드 런타임 env와 다름).
- 빌드가 일어나는 곳(GitHub Actions)에 주입해야 함:
  - `frontend/Dockerfile`: `ARG VITE_KAKAO_MAP_KEY` + `ENV` → `npm run build`.
  - deploy.yml 프론트 빌드 스텝: `build-args: VITE_KAKAO_MAP_KEY=${{ secrets.VITE_KAKAO_MAP_KEY }}`.
  - GitHub Secret에 `VITE_KAKAO_MAP_KEY`(JavaScript 키) 등록.
- 카카오 콘솔 Web 플랫폼에 도메인 등록(키의 도메인 화이트리스트).

---

## 18. 모든 매장이 자동으로 영업 종료됨
**문제**
관리자로 영업 시작해도 잠시 뒤 전 매장이 CLOSED로 바뀜.

**해결**
`StoreStatusScheduler`가 매 60초 영업시간을 `LocalTime.now()`와 비교해 상태를 갱신하는데, **서버 타임존이 UTC**라 KST 영업시간과 9시간 어긋나 대량 오작동. `TimeZoneConfig`(@PostConstruct)로 앱 기본 타임존을 `Asia/Seoul`로 고정.
> 스케줄러는 유지되므로, 영업시간 **밖**에 수동 OPEN하면 다시 CLOSED됨(정상 동작). 데모로 밤에 열어두려면 매장 영업시간을 00:00~23:59로 넓힘.

---

## 19. 장바구니 담기 500 (Metaspace OOM)
**문제**
`POST /api/cart/items` 500. 로그:
```
Caused by: java.lang.OutOfMemoryError: Metaspace
```
Redis·재고는 정상인데 시간이 지나면 발생.

**해결**
`-XX:MaxMetaspaceSize=128m`이 과소 → Spring Boot(~150~200MB) 클래스 메타데이터를 못 담아, 런타임 클래스 로딩 중 요청이 터짐. **Dockerfile ENTRYPOINT의 값을 256m로 상향**.
> ⚠️ **함정**: compose의 `JAVA_TOOL_OPTIONS`로 늘려도 **ENTRYPOINT의 명시적 인자가 덮어써서 무효**. JVM 메모리 튜닝은 Dockerfile ENTRYPOINT를 단일 소스로 관리할 것.

---

## 20. 메뉴 이미지가 배포 때마다 엑박
**문제**
업로드한 메뉴 이미지가 재배포 후 깨짐(엑박).

**해결**
업로드 파일이 컨테이너 임시 레이어(`/app/uploads/menus`)에 저장되는데 **영구 볼륨이 없어** 컨테이너 재생성(=매 배포, 특히 deploy가 `docker rm -f` 수행)마다 삭제됨. backend에 볼륨 마운트:
```yaml
volumes:
  - uploads-data:/app/uploads   # + 최상위 volumes 에 uploads-data 선언
```
- 프론트 nginx에 `/uploads/` → 백엔드 프록시도 필요(도메인 경유 서빙).
- 볼륨 없던 동안 올린 이미지는 이미 소실 → 볼륨 적용 후 재업로드해야 함.

---

## 최종 배포 구조 (2026-07-20 기준)
```
EC2 (t3.micro, RAM 1GB + 스왑 2GB)
├── Docker (docker-compose.prod.yml)
│   ├── whale-caddy     (리버스 프록시 / 자동 HTTPS, 80·443)
│   ├── whale-frontend  (React + nginx, 내부 80 / /api·/oauth2·/uploads 프록시)
│   ├── whale-backend   (Spring Boot, 8080)
│   ├── whale-postgres  (PostgreSQL, 5432)
│   └── whale-redis     (Redis, 6379)
├── 볼륨: postgres-data · redis-data · caddy-data · caddy-config · uploads-data
├── .env: JWT_SECRET · KAKAO_CLIENT_ID/SECRET · OAUTH2_REDIRECT_URI
└── 도메인: whaleorder.duckdns.org (DuckDNS) → Caddy 자동 인증서

접속: 브라우저 → https://whaleorder.duckdns.org
      → Caddy(443) → frontend nginx(80) → (/api·/oauth2·/login/oauth2·/uploads) backend:8080
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
