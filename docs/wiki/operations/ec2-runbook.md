# EC2 운영 런북 (t3.micro / 프리티어)

> EC2를 켠 뒤 무엇부터 할지, 느려서 안 켜질 때 어떻게 살릴지 정리한 운영 가이드.
> 배포 파이프라인 자체는 [배포 문서](deployment.md) 참조. 과거 삽질 사례는 [트러블슈팅](troubleshooting.md).

## 전제

- 인스턴스: **t3.micro (vCPU 2, RAM 1GB)** — 메모리가 매우 빠듯하다.
- 배포 컨테이너: **backend · frontend · postgres · redis 4개** (`docker-compose.prod.yml`).
  Kafka·모니터링은 제외 — [배포 문서 §프리티어 경량 배포](deployment.md) 참고.
- 컨테이너는 `restart: unless-stopped` 라 **부팅 시 자동 기동**된다.
- 앱 배포는 `main` push → GitHub Actions가 EC2에 SSH 접속해 자동 수행. 수동 배포 불필요.
- 레포 클론 경로: **`~/whale-order`** (소문자·하이픈). `.env`도 이 폴더 안에 둔다.

---

## 🔑 켠 직후 제일 먼저 — 스왑 확인

```bash
free -h        # Swap 이 2.0Gi 로 잡혀 있어야 정상
```

스왑이 없으면(0B) 컨테이너 자동 기동 중 OOM으로 박스가 다운된다. **가장 흔한 장애 원인.**

---

## A. 인스턴스를 재시작(stop → start)한 경우 — 확인 3가지

이미 세팅된 인스턴스면 손댈 게 거의 없다. 순서대로 확인만:

```bash
free -h                              # 1) 스왑 2.0Gi 확인 (0B면 아래 D-1 재실행)
docker ps                            # 2) 4개 컨테이너 Up 확인 (자동 기동됨)
docker logs -f whale-backend         # 3) "Started ... in N seconds" + 에러 없는지
```

컨테이너가 안 떠 있으면 수동 기동:

```bash
cd ~/whale-order && docker-compose -f docker-compose.prod.yml up -d
```

브라우저로 `http://<EC2-IP>:3000`(프론트) / `:8080`(API) 뜨면 끝.
**재시작 시 배포를 다시 돌릴 필요 없다.**

---

## B. 새 인스턴스 최초 세팅 — 이 순서 그대로

### 1. 스왑 2GB (반드시 맨 먼저 — 안 하면 이후 단계가 OOM으로 멈춤)

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # ← 재부팅 후 유지 핵심
free -h
```

### 2. Docker + docker-compose 설치

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose
sudo usermod -aG docker ubuntu       # sudo 없이 docker 사용 (재로그인 필요)
```

### 3. 레포 클론 + `.env` 생성 ⚠️ 가장 자주 빠뜨리는 단계

```bash
git clone https://github.com/LeeJiiYoung/WhaleOrder ~/whale-order
cd ~/whale-order
nano .env
```

`.env` 내용 (현재 `docker-compose.prod.yml`이 참조하는 값):

```
JWT_SECRET=32자이상랜덤문자열
KAKAO_CLIENT_ID=카카오앱키
KAKAO_CLIENT_SECRET=카카오시크릿
```

- `.env`는 `.gitignore`라 레포에 없다. **EC2에 직접 생성**해야 한다. 없으면 `JWT_SECRET`이 비어 토큰 발급이 깨진다.
- **반드시 `~/whale-order/.env`** 위치. compose가 이 폴더 기준으로 읽는다.

### 4. AWS 보안그룹 인바운드 포트

| Type | Port | 용도 |
|---|---|---|
| SSH | 22 | 접속 (내 IP만 권장) |
| Custom TCP | 8080 | 백엔드 API |
| Custom TCP | 3000 | 프론트 |
| HTTPS | 443 | *(Caddy 붙이면)* |

### 5. 첫 배포

`main`에 push → Actions 자동 배포. 또는 수동: `docker-compose -f docker-compose.prod.yml up -d`

---

## C. 느려서 안 켜질 때 — 응급 복구 (thrashing rescue)

t3.micro가 메모리 부족으로 스왑 지옥에 빠지면 SSH조차 느려진다.

### C-1. SSH도 안 될 만큼 느리면 → 콘솔에서 재부팅

1. AWS 콘솔 → EC2 → 인스턴스 **중지(Stop)** → 멈추면 → **시작(Start)**
2. 재시작되면 **빠르게 SSH 접속** (컨테이너가 다시 떠서 느려지기 전에)
3. 접속 즉시 숨통 틔우기:
   ```bash
   docker stop $(docker ps -q)      # 일단 전부 정지
   free -h                          # Swap 0B면 이게 원인
   ```

### C-2. 스왑부터 (제일 큰 지렛대)

위 [B-1](#1-스왑-2gb-반드시-맨-먼저--안-하면-이후-단계가-oom으로-멈춤) 명령 실행. 이 하나로 대부분 살아난다.

### C-3. 원인 확인

```bash
dmesg | grep -i "killed process"     # OOM Killer가 죽인 프로세스 (java/kafka가 보통 범인)
docker ps -a                         # 죽었다 살았다 반복하는 컨테이너
docker stats --no-stream             # 현재 메모리 점유
```

### C-4. 무거운 컨테이너가 아직 돌고 있다면

옛 8개 서비스 구성이 남아 있으면(kafka·모니터링) 필수 4개만 남긴다:

```bash
cd ~/whale-order
docker stop whale-kafka whale-kafka-ui whale-prometheus whale-grafana 2>/dev/null
docker rm   whale-kafka whale-kafka-ui whale-prometheus whale-grafana 2>/dev/null
docker-compose -f docker-compose.prod.yml up -d backend frontend postgres redis
```

근본 해결은 슬림 `docker-compose.prod.yml`을 **커밋 → push → Actions 배포**. `--remove-orphans`가 무거운 컨테이너를 자동 제거한다.

---

## 자주 쓰는 명령어

```bash
# 상태 / 로그
docker ps
docker logs -f whale-backend
docker stats --no-stream

# 재기동 / 정지
docker-compose -f docker-compose.prod.yml up -d
docker-compose -f docker-compose.prod.yml down

# 모니터링 필요할 때만 잠깐 (t3.micro에선 부담되니 확인 후 down)
docker-compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml up -d

# DB 접속
docker exec -it whale-postgres psql -U whale -d whaleorder
```

---

## 흔한 함정 Top 3

| 함정 | 증상 | 해결 |
|------|------|------|
| 스왑을 `swapon`만 하고 fstab 등록 안 함 | 재부팅 후 스왑 사라져 다운 | `/etc/fstab`에 등록 |
| `.env` 없음 / 엉뚱한 폴더 | 토큰 발급 실패, 앱 기동 오류 | `~/whale-order/.env` 생성 |
| 옛 무거운 구성(kafka+모니터링) 잔존 | 1GB에서 OOM | 슬림 compose 배포 or C-4 수동 정리 |

## 관련 문서

- [배포 (Docker / EC2)](deployment.md) — 파이프라인, 경량 배포 상세
- [트러블슈팅](troubleshooting.md) — 과거 삽질 10선
- [모니터링](monitoring.md)
