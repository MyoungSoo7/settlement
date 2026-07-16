---
name: project_kotlin_notification_reconciliation
description: notification-service·reconciliation-service (Kotlin) 구조·컨벤션·oo-score 채점 이력(2026-07-16)
metadata:
  type: project
---

## 위치·성격
- `notification-service/src/main/kotlin/github/lms/lemuel/notification/**`,
  `reconciliation-service/src/main/kotlin/github/lms/lemuel/reconciliation/**` — 메인 13개 Java/Kotlin MSA와
  별개로 존재하는 **경량 Kotlin 마이크로서비스 2종** (Spring Boot 3.3.5, Kotlin 2.0.21, JDK21 pinned).
- 둘 다 **독립 Gradle 빌드**(루트 `settings.gradle.kts` 13서비스 목록과 무관, `shared-common` 미의존, 서로도 미의존).
  build.gradle.kts 확인 결과 MSA 경계·코드 의존 0건.
- notification: 알림 디스패치(채널: log/email/slack, dedupe, kafka consumer + REST).
  reconciliation: 대사 엔진(순수 도메인 diff) + 동시 fetch(coroutine) + 스케줄러/REST.

## 구조 컨벤션 (Java 서비스와 다른 점 — 감점 사유 아님)
- **`application/` 이 flat** — `application/port/{in,out}/` 서브패키지 분리 없이 포트 인터페이스와 무관하게 같은
  패키지에 위치 (`DispatchNotificationUseCase`/`DedupeStore`/`NotificationChannel` 모두 `application/` 직속).
  KDoc 주석으로 포트 방향은 명시됨(in/out). Kotlin 관용구·서비스 내부 일관 → oo-score 공정성 조항상 비감점.
- domain 패키지는 adapter/프레임워크 import 0건(grep 실측, 2026-07-16 기준).
- 두 서비스 모두 God class 없음(최대 92라인, `NotificationDispatcher.kt`).
- 도메인 값타입 전부 `val`-only data class, public setter 0건.

## 반복 발견 패턴 (oo-score 축⑤ 감점 사유)
- 두 서비스 모두 도메인 검증에 Kotlin stdlib `require()` → `IllegalArgumentException` 사용, 도메인 전용 예외
  타입 없음. 예: `Notification.kt:29-30`, `ReconciliationEngine.kt:21`, `ReconciliationService.kt:48-49`.
  HTTP 매핑은 `*ExceptionHandler.kt`(둘 다 `@RestControllerAdvice` + `IllegalArgumentException` 캐치)로
  정상 처리(400), 컨텍스트 보존. **삼킴 아님** — 단지 도메인/인프라 예외 타입 미분리가 유일한 실측 결함.
  이 프로젝트의 "금융 5서비스 generic IAE 금지" 가드(CLAUDE.md)는 이 두 서비스에는 적용 대상 아님(경량 유틸 서비스).

## oo-score 채점 이력
- **2026-07-16 (읽기전용 패널 리뷰, 5축)**: 실측 결함은 예외설계(축⑤) 한 가지 패턴뿐, 나머지 4축은 결함 없음.
  - SCORES[notification]: [9.7, 9.8, 9.7, 9.8, 9.3]
  - SCORES[reconciliation]: [9.8, 9.7, 9.7, 9.8, 9.3]
  - 특기사항: `HttpSources.kt`(reconciliation)는 미배선 스켈레톤임을 주석으로 명문화 → 공정성 조항 적용 비감점.
    `ReconciliationReport.discrepancies`는 방어적 복사 없으나 원본이 지역변수라 실질 변조 경로 없어 결함 아님으로 판정.
