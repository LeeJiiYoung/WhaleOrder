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
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]