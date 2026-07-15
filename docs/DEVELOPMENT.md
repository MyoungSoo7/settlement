# 개발 참조 — Lemuel

> CLAUDE.md 에서 분리한 **참조성 정보**(기술 스택·빌드 커맨드·인프라·작업 이력).
> 에이전트가 매 대화에 상주시킬 필요는 없고 필요할 때 조회한다. 강제 규칙·가드레일·DoD 는 [`CLAUDE.md`](../CLAUDE.md) 참조.

## 기술 스택

| 구분 | 기술 | 구분 | 기술 |
|------|------|------|------|
| 언어 | Java 25 | 메시지 | Kafka (Redpanda 호환) |
| 프레임워크 | Spring Boot 4.0.4 | PG | Toss Payments |
| 빌드 | Gradle 멀티모듈 (Kotlin DSL) | 배치 | Spring Batch |
| Gateway | Spring Cloud Gateway 2025 | 캐시 | Caffeine(L1) + 선택 Redis(L2) |
| DB | PostgreSQL 17 | PDF | iText 8 |
| 검색 | Elasticsearch 8.17 | 마이그레이션 | Flyway (V1~V50 + `V{timestamp}__` 혼재) |
| 관측 | Micrometer + Prometheus | 회복탄력성/RateLimit | Resilience4j / Bucket4j |

> Boot 4 / Java 25 조합의 알려진 함정(레거시 ObjectMapper 빈 부재, RestClient.Builder 자체 빈 필요,
> 네이티브 @Query 구조적 SpEL 미평가, ArchUnit 1.4.x+ 필요 등)은 각 서비스 코드·ADR 참조.

## 빌드 및 실행 커맨드

```bash
./gradlew build                                     # 전체 빌드
./gradlew :<module>:compileJava                     # 모듈별 컴파일 (예: :order-service:compileJava)
./gradlew :<module>:test                            # 모듈별 테스트
./gradlew :<module>:jacocoTestCoverageVerification  # 커버리지 게이트(측정 정답, LINE 90%)
./gradlew :<module>:bootRun                         # 모듈별 부트런
./gradlew :<module>:bootJar                         # 모듈별 jar

# 모듈: shared-common, order-service, settlement-service, loan-service, financial-statements-service,
#       economics-service, company-service, operation-service, market-service, ai-service,
#       common-data-service, investment-service, account-service, gateway-service

# Docker
docker compose up -d                                # DB-per-service PG 12종 · ES · Redpanda · 12 services + gateway
docker build --build-arg MODULE=<service> -t lemuel-<name> .   # 컨테이너 이미지 (MODULE 로 서비스 지정)
```

> `bootRun` 은 `.env` 를 자동으로 읽지 못한다 — 필요한 env 는 `--args` 또는 System property 로 주입.

## 인프라

- 컨테이너: Docker Compose(로컬), Kubernetes(운영). 리버스 프록시: gateway-service.
- 모니터링: Prometheus + Micrometer + Grafana + OTLP. 메시지: Redpanda(Kafka 호환).

## 작업 이력 / 브랜치 정보

- **메인 라인**: `develop` → `main`. main 은 보호 브랜치(PR 필수, squash 만, 필수 CI 2종). 분리 전 백업 `backup/pre-msa-split`.
- **MSA 분리 완료**: 12 서비스 + DB-per-service, settlement↔order 이벤트 프로젝션(ADR 0020).
- **제거된 도메인**: `reservation`(시공 예약) — 모듈·DB·라우팅·프론트·k8s 정리 완료.
- **TPS 개선**: PgBouncer→Redpanda, settlement 배치/컨슈머 스레드·프로젝션 쿼리·캐시·PDF 비동기화 등.
