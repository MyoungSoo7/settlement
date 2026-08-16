############################
# Stage 1: Build (parameterized)
#   docker build --build-arg MODULE=order-service .
#   docker build --build-arg MODULE=settlement-service .
#   docker build --build-arg MODULE=gateway-service .
############################
FROM gradle:9.7.0-jdk25 AS builder
ARG MODULE
WORKDIR /workspace

# 의존성 캐싱: 변경 적은 파일 먼저
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# shared-common 은 독립 빌드(includeBuild) — 합성 설정에 자체 settings 가 필요
COPY shared-common/settings.gradle.kts shared-common/build.gradle.kts ./shared-common/
COPY order-service/build.gradle.kts ./order-service/
COPY settlement-service/build.gradle.kts ./settlement-service/
COPY loan-service/build.gradle.kts ./loan-service/
COPY financial-statements-service/build.gradle.kts ./financial-statements-service/
COPY company-service/build.gradle.kts ./company-service/
COPY operation-service/build.gradle.kts ./operation-service/
COPY economics-service/build.gradle.kts ./economics-service/
COPY market-service/build.gradle.kts ./market-service/
COPY ai-service/build.gradle.kts ./ai-service/
COPY common-data-service/build.gradle.kts ./common-data-service/
COPY investment-service/build.gradle.kts ./investment-service/
COPY account-service/build.gradle.kts ./account-service/
COPY organization-service/build.gradle.kts ./organization-service/
COPY card-service/build.gradle.kts ./card-service/
COPY insurance-service/build.gradle.kts ./insurance-service/
COPY deposit-service/build.gradle.kts ./deposit-service/
COPY board-service/build.gradle.kts ./board-service/
COPY education-service/build.gradle.kts ./education-service/
COPY gateway-service/build.gradle.kts ./gateway-service/

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:dependencies || true

# 전체 소스 복사
COPY shared-common ./shared-common
COPY order-service ./order-service
COPY settlement-service ./settlement-service
COPY loan-service ./loan-service
COPY financial-statements-service ./financial-statements-service
COPY company-service ./company-service
COPY operation-service ./operation-service
COPY economics-service ./economics-service
COPY market-service ./market-service
COPY ai-service ./ai-service
COPY common-data-service ./common-data-service
COPY investment-service ./investment-service
COPY account-service ./account-service
COPY organization-service ./organization-service
COPY card-service ./card-service
COPY insurance-service ./insurance-service
COPY deposit-service ./deposit-service
COPY board-service ./board-service
COPY education-service ./education-service
COPY gateway-service ./gateway-service

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon :${MODULE}:bootJar -x test

# bootJar 결과를 고정 경로로 복사 (Spring Boot 가 만드는 *-plain.jar 는 제외)
RUN find /workspace/${MODULE}/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

############################
# Stage 2: Runtime
############################
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl tini ghostscript
RUN addgroup -S spring && adduser -S spring -G spring

# 쓰기 가능한 데이터 경로를 **이미지 안에 미리 만들고 소유권을 넘긴다**.
#
# 이유: 컨테이너는 비루트(spring)로 돌지만, named volume 의 마운트 지점이 이미지에 없으면
# Docker 가 그 디렉터리를 root:root 로 만들어 붙인다 → 첨부 업로드가 Permission denied 로 죽는다.
# 이미지에 있으면 Docker 가 그 소유권을 볼륨에 그대로 복사하므로 spring 이 쓸 수 있다.
# (로컬 bootRun 에서는 자기 계정으로 쓰기 때문에 절대 드러나지 않는 종류의 사고다 — 실측으로 잡았다.)
RUN mkdir -p /var/lib/lemuel/board-attachments \
    && chown -R spring:spring /var/lib/lemuel

USER spring:spring

WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
