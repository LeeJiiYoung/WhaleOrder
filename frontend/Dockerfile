# ── 1단계: 빌드 ──────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci

COPY . .

# Vite 환경변수(VITE_*)는 런타임이 아니라 이 build 시점에 번들에 박힌다.
# 따라서 서버 .env 가 아니라 build-arg 로 주입해야 운영 번들에 키가 들어간다.
# (카카오 지도 JS 키는 브라우저에 노출되는 클라이언트 키라, 콘솔의 도메인 화이트리스트로 보호한다)
ARG VITE_KAKAO_MAP_KEY
ENV VITE_KAKAO_MAP_KEY=$VITE_KAKAO_MAP_KEY

RUN npm run build

# ── 2단계: Nginx로 정적 파일 서빙 ────────────────────────────────
FROM nginx:alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80