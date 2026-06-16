# Settlement standalone 승격 체크리스트 (ADR 0020 Phase 0)

- 일자: 2026-06-16
- 연관: [ADR 0020 — order↔settlement DB 분리](../adr/0020-order-settlement-db-split.md) Phase 0
- 상태: 계획 (실행 시 승인 후 진행)

## 목표 / 범위

settlement-service 를 **독립 프로세스로 기동**(:8082)시켜 order-service fat jar 번들에서 떼어낸다.
**이 단계는 DB 를 분리하지 않는다** — 여전히 공유 `opslab` DB 를 본다(DB 물리 분리는 Phase 4).
즉 "프로세스 독립 + 공유 DB" 중간 상태가 Phase 0 의 산출물이다.

## 현재 상태 (사실)

| 항목 | 현재 |
|---|---|
| `settlement-service/build.gradle.kts` | `bootJar` **비활성**, `jar` 활성 (라이브러리 모드) |
| 프로덕션 `@SpringBootApplication` | **없음** (테스트 부트스트랩 `src/test/.../SettlementServiceApplication` 만 존재) |
| order-service 번들 | `order-service/build.gradle.kts:12` `implementation(project(":settlement-service"))` 로 같은 jar 에 포함 |
| docker-compose | `SETTLEMENT_SERVICE_URI: http://order-service:8080` (게이트웨이가 정산 경로를 order 로 보냄) |
| 스타터 | web·actuator·security·kafka·ES·batch·flyway **이미 보유** (의존성은 준비됨) |
| 마이그레이션 | order-service 가 전부 소유 (settlement 자체 마이그레이션 없음) |

→ 의존성은 이미 완비. 막는 건 (a) bootJar, (b) 프로덕션 main, (c) 번들 의존, (d) 라우팅뿐.

## 체크리스트

### 1. bootJar 재활성 — `settlement-service/build.gradle.kts`
- [ ] `bootJar { enabled = false }` 블록 제거 → bootJar 활성
- [ ] `jar { enabled = true; archiveClassifier = "" }` 오버라이드 제거 (실행가능 jar 로 복귀)

### 2. 프로덕션 진입점 추가 — `settlement-service/src/main/java/github/lms/lemuel/SettlementServiceApplication.java`
- [ ] `@SpringBootApplication` main 클래스 신설 (component scan `github.lms.lemuel` → settlement + shared-common)
- [ ] 테스트 부트스트랩(`src/test/.../SettlementServiceApplication`)과 **중복 정리** — 통합테스트가 프로덕션 클래스를 `classes = ` 로 쓰도록 전환
- [ ] MSA 경계 확인: order-service 는 test/runtime classpath 에 없음 → scan 에 안 잡힘 (그대로 유지)

### 3. order 번들 해제 — `order-service/build.gradle.kts`
- [ ] `implementation(project(":settlement-service"))` (line 12) **제거**
- [ ] 빌드 후 order jar 에 `github.lms.lemuel.settlement.*` 클래스가 **없음** 확인

### 4. Flyway 소유권 — Phase 0 은 "건드리지 않음"
- [ ] settlement 프로덕션 설정에서 `spring.flyway.enabled=false` (공유 DB 스키마는 order 가 소유 — 이중 마이그레이션 금지)
- [ ] (선택) `validate-on-migrate` 로 스키마 검증만
- [ ] 주: 마이그레이션 소유권의 실제 이관은 **Phase 4(DB 분리)** 항목 — 여기서 하지 않음

### 5. 런타임 설정 — `settlement-service/src/main/resources/application.yml`
- [ ] `server.port: 8082` 확인 (이미 설정됨)
- [ ] datasource → 공유 `opslab` 그대로 (Phase 0)
- [ ] kafka/ES/batch 활성 플래그 확인 (`app.kafka.enabled` 등)

### 6. 컨테이너 / 오케스트레이션
- [ ] `docker-compose.yml` 에 `settlement-service` 서비스 추가 (`build --build-arg MODULE=settlement-service`, 포트 8082, `depends_on`: postgres·redpanda·elasticsearch)
- [ ] Dockerfile 변경 불필요 (이미 `MODULE` 빌드인자 파라미터화)
- [ ] k8s: settlement Deployment/Service 매니페스트 추가 (order 와 동일 패턴)

### 7. 게이트웨이 라우팅
- [ ] `docker-compose.yml` 의 `SETTLEMENT_SERVICE_URI` → `http://settlement-service:8080`(컨테이너 내부 포트) 로 변경 (현재 `order-service:8080`)
- [ ] gateway 라우트(`/api/settlements/**` 등)는 이미 존재 → URI 만 교체

### 8. 헬스/관측
- [ ] actuator liveness/readiness 노출 (이미 actuator 보유) — k8s 프로브용
- [ ] settlement 메트릭이 Prometheus 에 별도 타깃으로 스크랩되는지 확인

### 9. 테스트 / 스킬 갱신
- [ ] 기존 settlement 통합테스트(Testcontainers)가 새 프로덕션 main 으로 부팅되게 정리
- [ ] `settlement-integration-test` 스킬 노트 갱신 — "라이브러리 모드 전제" 경고는 이 단계로 무효화됨 (스킬 자체가 "Phase B standalone 도입 시 갱신" 명시)

## 수용 기준 (검증)
- [ ] `./gradlew :settlement-service:bootRun` 단독 기동 → :8082 응답
- [ ] `docker compose up` 시 settlement 가 별도 컨테이너로 뜨고, 게이트웨이가 `/api/settlements/**` 를 그쪽으로 라우팅
- [ ] order jar 에 settlement 클래스 미포함
- [ ] 기존 단위·통합 테스트 전부 통과 (무회귀)

## 롤백
- bootJar 재비활성 + jar 오버라이드 복귀
- order-service 에 `implementation(project(":settlement-service"))` 재추가
- gateway `SETTLEMENT_SERVICE_URI` 를 order 로 원복

## 범위 밖 (후속 Phase)
- 이벤트 페이로드 enrich (Phase 1)
- CQRS 로컬 read model + 읽기 컷오버 (Phase 2~3)
- **DB 물리 분리 + 마이그레이션 소유권 이관** (Phase 4)
- shared-common 플랫폼 라이브러리화 ([ADR 0021](../adr/0021-shared-common-as-platform-library.md))
