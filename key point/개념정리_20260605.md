# Key Points

## Kafka KRaft 모드

Zookeeper 없이 Kafka만으로 운영하는 방식. Kafka 2.8+부터 지원, Kafka 4.0부터 Zookeeper 완전 제거 예정.

**기존 방식 (Zookeeper)**
```
Zookeeper → Kafka 메타데이터 관리
```

**KRaft 방식**
```
Kafka 자체적으로 메타데이터 관리 (broker + controller 역할 동시 수행)
```

필수 환경변수:
```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
CLUSTER_ID: # 고정값 필요
```

---

## docker-compose 환경 분리

| 파일 | 용도 |
|------|------|
| `docker-compose.yml` | 로컬 개발 - 인프라만 (DB, Redis, Kafka 등) |
| `docker-compose.prod.yml` | EC2 운영 - 전체 스택 (백+프론트 포함) |

로컬에서는 백/프론트를 직접 실행하고 인프라만 Docker로 띄우는 방식.

---

## GitHub Actions CI/CD 흐름

```
git push (main)
  → build-and-push job
      → 백엔드 이미지 빌드 & Docker Hub push
      → 프론트엔드 이미지 빌드 & Docker Hub push
  → deploy job
      → EC2 SSH 접속
      → git pull (docker-compose.prod.yml 최신화)
      → docker-compose pull & up
```

**GitHub Secrets 필요 목록**
- `DOCKER_USERNAME` - Docker Hub 사용자명
- `DOCKER_PASSWORD` - Docker Hub 비밀번호 또는 Access Token
- `EC2_HOST` - EC2 퍼블릭 IP
- `EC2_SSH_KEY` - .pem 파일 전체 내용

---

## Nginx 설정 (React Router)

React Router 사용 시 새로고침하면 404가 뜨는 문제가 있음.  
Nginx에서 모든 요청을 `index.html`로 보내도록 설정 필요.

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

API 요청은 백엔드로 프록시:
```nginx
location /api/ {
    proxy_pass http://backend:8080;
}
```

---

## 오류 모음

### 1. workflow 권한 오류
```
refusing to allow a Personal Access Token to create or update workflow without `workflow` scope
```
**원인**: GitHub PAT에 workflow 권한 없음  
**해결**: GitHub → Settings → Developer settings → Personal access tokens → 토큰 편집 → `workflow` 체크

---

### 2. 컨테이너 이름 충돌
```
Conflict. The container name "/whale-postgres" is already in use
```
**원인**: 이전에 실행했던 컨테이너가 남아있음  
**해결**:
```bash
docker rm -f whale-postgres whale-backend whale-redis ...
docker-compose -f docker-compose.prod.yml up -d
```

---

### 3. docker-compose 'ContainerConfig' 오류
```
container.image_config['ContainerConfig'].get('Volumes') or {}
```
**원인**: docker-compose 1.29.2 버전 버그 (최신 Docker 이미지와 호환 안 됨)  
**해결**: docker-compose v2로 업그레이드
```bash
sudo apt-get remove docker-compose -y
sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-linux-x86_64" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
```

---

### 4. JwtProvider 빈 생성 실패
```
Error creating bean with name 'jwtProvider': Unexpected exception during bean creation
```
**원인**: `JWT_SECRET` 환경변수 누락  
**해결**: docker-compose.prod.yml 백엔드 환경변수에 추가
```yaml
JWT_SECRET: ${JWT_SECRET}
```
EC2에 `.env` 파일 생성:
```bash
cat > .env << 'EOF'
JWT_SECRET=32자_이상_랜덤_문자열
EOF
```

---

### 5. docker-compose 명령어 not found
```
-bash: /usr/bin/docker-compose: No such file or directory
```
**원인**: 설치 경로가 `/usr/local/bin`인데 PATH가 `/usr/bin`을 참조  
**해결**:
```bash
sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
```

---

### 6. network whale-net not found
```
failed to set up container networking: network whale-net not found
```
**원인**: EC2에 Docker 네트워크가 생성되지 않음  
**해결**: docker-compose up 실행 시 자동 생성됨. 수동으로 만들려면:
```bash
docker network create whale-net
```

---

## EC2 초기 세팅 체크리스트

```bash
# Docker 자동시작 설정
sudo systemctl enable docker

# whale-order 디렉토리 생성 및 배포
mkdir ~/whale-order
cd ~/whale-order
git clone https://github.com/LeeJiiYoung/WhaleOrder .

# .env 파일 생성
cat > .env << 'EOF'
JWT_SECRET=your_secret_key
EOF

# 전체 스택 실행
docker-compose -f docker-compose.prod.yml up -d
```