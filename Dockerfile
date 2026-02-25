############################
# Stage 1: Build
############################
FROM gradle:9.3-jdk21-alpine AS builder
WORKDIR /workspace

# 1) 의존성 캐싱을 최대한 살리기 위해 "변경 적은 파일" 먼저 복사
COPY settings.gradle* build.gradle* gradle.properties* ./
COPY gradle ./gradle

# (선택) 멀티모듈이면 여기에 각 모듈의 build.gradle도 먼저 COPY 하는 게 더 좋습니다.
# 예: COPY app/build.gradle.kts app/settings.gradle.kts ./app/

# 2) dependencies 먼저 당겨서 캐시 레이어 확보 (BuildKit cache mount)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon -q dependencies || true

# 3) 실제 소스 복사
COPY src ./src

# 4) 빌드 (테스트는 필요 시 켜세요)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon bootJar -x test


############################
# Stage 2: Runtime
############################
FROM eclipse-temurin:21-jre-alpine

# tini: PID1 시그널/좀비 프로세스 처리 (graceful shutdown 안정)
RUN apk add --no-cache curl tini

# non-root 유저
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8080

# actuator가 없거나 경로가 다르면 /actuator/health 대신 변경하세요.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# 컨테이너 메모리 인지 옵션
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# exec form + tini
ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
