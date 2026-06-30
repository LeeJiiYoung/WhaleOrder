# ── 1단계: 빌드 ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src

RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# ── 2단계: 실행 (JRE만 포함, 이미지 경량화) ──────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# t2.micro(1GB) 기준 JVM 메모리 제한
# - SerialGC: 작은 힙에서 G1보다 메모리/CPU 오버헤드 적음
# - MaxMetaspaceSize: 오프힙 Metaspace 무한 증가 차단
# - Xss512k: 스레드당 스택을 절반으로 줄여 RSS 절감
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]