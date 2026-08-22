# Seed — education-service 과정·차시 저작 as-is 사양

> **상태: CONFIRMED** (2026-08-22 역산) · 정본 데이터: [`education-service-course-authoring.seed.yaml`](education-service-course-authoring.seed.yaml)
> 자매 문서: [`../prd/education-service.md`](../prd/education-service.md)
>
> | 판 | 일자       | 대조 기준             | 비고                                    |
> | -- | ---------- | --------------------- | --------------------------------------- |
> | v1 | 2026-08-22 | `develop` `92d25c463` | 최초 결정화 (PRD 역산과 같은 기준 커밋) |
>
> **원칙**: 이 Seed 는 "현행 코드가 실제로 하는 일"의 불변 기술이다. 결함은 교정하지 않고
> Known Issues 로만 기록한다.

## Goal (한 줄)

**education-service 의 과정·차시 저작 루프(상태머신 4상태 · 차시 순서 불변식 · 소속 대조 · 공개
이벤트 적재)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 ·
학습자 경로를 붙일 때의 베이스 · 면접/포트폴리오 문서로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| Course 상태머신(DRAFT/PUBLISHED/HIDDEN/CLOSED) | 수강·진도·이수 (미구현) |
| Lesson 순서 불변식 · 재정렬 2단 저장 | 콘텐츠 저장 (`content_ref` 참조만 보관) |
| 차시 소속 대조(IDOR 성 오조작 차단) | 학습자 공개 조회 경로 (존재하지 않음) |
| 감사 로그 적재 | 프론트 화면(`/admin/system/education`) 내부 |
| `CoursePublished` Outbox 적재 계약 표면 | Kafka 실제 발행 (→ KI-1, 미배선) |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **Course 상태머신** — 4상태. 전이 판정은 애그리거트가 소유하며 각 전이 메서드가 허용 원천을
   검사한다: `publish`(`Course.java:58`) ← DRAFT·HIDDEN, `hide`(`:65`) ← PUBLISHED,
   `close`(`:71`) ← PUBLISHED·HIDDEN. 위반은 `InvalidCourseStateException`(`require`, `:78`).
   `CLOSED` 는 종단 — 나가는 전이가 없다. DB CHECK 가 같은 4값을 강제한다(`V1:15`).
2. **생성은 항상 DRAFT** — 다른 상태로 만드는 공개 경로가 없다(`Course.draft`, `:41`).
   영속 복원은 `rehydrate`(`:46`) 전용이며 전이 규칙을 우회하지 않는다.
3. **차시 순서 유일성** — `(course_id, sequence)` UNIQUE(`V1:33`).
4. **재정렬은 전량 교체** — 요청이 그 과정의 차시를 **정확히 한 번씩** 담아야 한다
   (`Lesson.validateReorder`, `Lesson.java:98`). 부분 재정렬 경로는 없다.
5. **재정렬은 음수 구간을 경유하는 2단 저장** — UNIQUE 제약 때문에 맞바꿈 중간 상태에서 값이
   겹치므로, 전부 `-1..-n` 으로 민 뒤 `1..n` 을 쓴다(`LessonAdminService.java:78`).
   `changeSequence`(`Lesson.java:77`)가 음수를 허용하는 이유가 이것이다.
6. **차시 소속 대조** — 수정·삭제는 경로의 `courseId` 와 차시의 소속을 대조한다
   (`Lesson.requireBelongsTo`, `Lesson.java:90` ← `LessonAdminService.java:50,62`).
   불일치는 404 `LESSON_NOT_IN_COURSE`. 대조가 어댑터가 아니라 도메인에 있다.
7. **삭제는 멱등, 소속 위반은 거부** — 없는 차시 삭제는 조용히 통과하고, 존재하는데 소속이 다르면
   거부한다(`LessonAdminService.java:62`).
8. **공개 전이에서만 이벤트** — 수정·숨김·종료는 발행하지 않는다
   (`CourseAdminService.java:76` — `if (target == PUBLISHED)`).
9. **자기호출 프록시 우회 금지** — 쓰기 메서드는 `@Transactional` 붙은 `get()` 이 아니라 애노테이션
   없는 `findOrThrow`(`CourseAdminService.java:53`)를 부른다(aop-proxy-gate 대응).
10. **도메인은 스프링을 모른다** — 예외→HTTP 번역은 어댑터(`EducationExceptionHandler`) 몫이며
    `BusinessException` 상속을 쓰지 않는다(ArchUnit 강제).

## 발행 이벤트 계약 (1토픽)

`lemuel.education.course_published` — `aggregateType=Education`, `eventType=CoursePublished`,
orderingKey `courseId`. 페이로드: `courseId`·`title`·`publishedAt`·`publishedBy`·`version`.

**소비 0.** 그리고 **Kafka 로 나가지 않는다** — KI-1 참조.

## 오류 계약

| 코드 | 상태 | 발생 |
|---|---|---|
| `COURSE_NOT_FOUND` | 404 | 없는 과정 조회·수정·전이 |
| `COURSE_INVALID_STATE` | 400 | 허용되지 않는 상태 전이 |
| `LESSON_ORDER_INVALID` | 400 | 재정렬 요청이 차시 목록과 불일치 |
| `LESSON_NOT_IN_COURSE` | 404 | 경로의 과정에 속하지 않는 차시 |

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | Course 상태머신 전이표 일치, 비정상 전이 차단 | `:education-service:test` — `CourseTest` |
| AC-2 | 차시 순서·소속 불변식 | `LessonTest` · `LessonAdminServiceTest` |
| AC-3 | 오류 응답이 전 서비스 공통 `ErrorResponse` 스키마 | `EducationErrorContractTest` · `EducationExceptionHandlerTest` |
| AC-4 | 헥사고날 의존 방향 위반 0 | `EducationArchitectureTest` (ArchUnit) |
| AC-5 | LINE ≥ 90% | `:education-service:jacocoTestCoverageVerification` |
| AC-6 | 공개 전이에서만 이벤트 적재, 페이로드 직렬화 성공 | `CoursePublicationEventTest` · `OutboxBackedEducationEventPublisherTest` |
| AC-7 | Outbox 폴러 배선 | `outbox-poller-gate.test.mjs` — **현재 KNOWN_UNWIRED 등록 상태**(KI-1) |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** *(해소 2026-08-22)*: 공개 이벤트가 Kafka 로 나가지 않았다 — `spring-kafka` 의존·
  `bootstrap-servers` 설정·폴러 빈·`@EnableScheduling` 이 전부 없었다. 네 축을 모두 배선했다.
  **네 번째가 함정이었다**: `@Scheduled` 는 `OutboxPollingTrigger` 에 붙어 있어 스케줄링이 꺼져
  있으면 빈이 등록만 된 채 영영 돌지 않고, 기동 로그에도 API 응답에도 증상이 없다. PRD G-1 참조.
- **KI-2**: **학습자 경로가 없다.** 공개 API 0, 전 경로 `hasRole('ADMIN')`. 즉 `PUBLISHED` 는 현재
  관리 목록의 라벨일 뿐이다. PRD G-2.
- **KI-3** *(해소 2026-08-23)*: `server.port` 기본값 8115 가 board-service 의
  `management.server.port` 와 겹쳐 **로컬 동시 기동은 나중에 뜬 쪽이 실패**했다(compose 는 컨테이너
  내부 8080 이라 드러나지 않았다). **8116/8117** 로 옮기고 gateway 기본 URI·compose 호스트 매핑·
  문서 8곳을 함께 고쳤다. PRD G-3 참조.
- **KI-4**: `LessonStatus.HIDDEN` 에 도달할 애플리케이션 경로가 없다. `status` 가 `final` 이고
  `create()` 는 항상 `ACTIVE` 를 넣으며 상태 변경 메서드가 없다 — `rehydrate` 로만 들어올 수 있다.
- **KI-5**: 삭제 감사 로그가 **실제로 지운 것과 지울 게 없던 것을 구분하지 못한다** —
  `delete()` 가 존재 여부와 무관하게 `LESSON_DELETED` 를 남긴다(멱등의 대가).
- **KI-6**: 재정렬 비용이 차시 수에 비례한다(항상 2n 회 UPDATE + 낙관적 락 버전 증가). 인터페이스가
  전량 교체라 호출측이 "둘만 바꿨다"를 알릴 방법이 없다.
- **KI-7**: `Course.publish` 가 `Instant.now()` 를 직접 부른다(시계 미주입) — 테스트에서
  `publishedAt` 고정 불가.
- **KI-8**: `education_audit_logs` 에 파티셔닝이 없다(common-data·financial 등은 파티션 + 런웨이 러너 보유).
