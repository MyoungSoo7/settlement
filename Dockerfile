############################
# Stage 1: Build (parameterized)
#   docker build --build-arg MODULE=order-service .
#   docker build --build-arg MODULE=settlement-service .
#   docker build --build-arg MODULE=gateway-service .
############################
FROM gradle:9.1.0-jdk25 AS builder
ARG MODULE
WORKDIR /workspace

# 의존성 캐싱: 변경 적은 파일 먼저
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY shared-common/build.gradle.kts ./shared-common/
COPY order-service/build.gradle.kts ./order-service/
COPY settlement-service/build.gradle.kts ./settlement-service/
COPY gateway-service/build.gradle.kts ./gateway-service/

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:dependencies || true

# 전체 소스 복사
COPY shared-common ./shared-common
COPY order-service ./order-service
COPY settlement-service ./settlement-service
COPY gateway-service ./gateway-service

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:bootJar -x test

# bootJar 결과를 고정 경로로 복사
RUN cp /workspace/${MODULE}/build/libs/*.jar /workspace/app.jar

############################
# Stage 2: Runtime
############################
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl tini ghostscript
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
