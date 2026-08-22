# Education Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independently deployable `education-service` that provides ADMIN-only course and lesson content management with an outbox-backed `CoursePublished` event.

**Architecture:** Add a Spring Boot service with its own PostgreSQL schema/database and hexagonal package structure. The service owns course, lesson, instructor, and publication state; it consumes no other service database and publishes only through the existing outbox boundary. The frontend receives one ADMIN-only education route through the existing gateway and menu system.

**Tech Stack:** Java, Spring Boot, Spring Data JPA, Flyway, PostgreSQL, Spring Security, shared-common, JUnit 5, MockMvc, Testcontainers, React, TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-16-education-service-design.md`

> **Superseded detail (2026-08-23):** the local port below (`8115`) collided with board-service's
> `management.server.port`. The service now runs on **8116** (management **8117**) — see
> `docs/plan/prd/education-service.md` G-3. This plan is kept as the historical record.

## Global Constraints

- Keep the hexagonal boundary: domain packages do not import Spring, JPA, web, or adapter classes.
- Use DB-per-service; no education code may query order, member, board, or settlement tables directly.
- Use the existing outbox pattern for `CoursePublished`; do not call Kafka directly from application or web code.
- Enforce `ROLE_ADMIN` in the backend even when the frontend hides the menu.
- Use optimistic locking for mutable course and lesson records.
- Use Flyway migrations and `ddl-auto=validate` for the service schema.
- Every new behavior is implemented test-first: write the failing test, run it and observe the expected failure, then implement the minimum behavior.
- Do not commit unrelated existing worktree changes.

### Task 1: Register the service and local runtime

**Files:**
- Modify: `settings.gradle.kts`
- Create: `education-service/build.gradle.kts`
- Create: `education-service/src/main/java/github/lms/lemuel/education/EducationServiceApplication.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/config/PersistenceConfig.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/config/SecurityConfig.java`
- Create: `education-service/src/main/resources/application.yml`
- Modify: `gateway-service/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Test: `education-service/src/test/java/github/lms/lemuel/education/EducationServiceApplicationTest.java`

**Interfaces:**
- Produces a bootable `:education-service:test` Gradle module and gateway route `/api/education/**` to `EDUCATION_SERVICE_URI`.
- The application scans only `github.lms.lemuel.education` and imports the shared JWT/security configuration explicitly, matching the isolation pattern used by `board-service`.

- [ ] **Step 1: Write the failing boot test**

  Create a context test asserting the application starts with the education package scan and that the configured service health endpoint is available.

- [ ] **Step 2: Run the test to verify it fails**

  Run `./gradlew :education-service:test --tests '*EducationServiceApplicationTest'`.
  Expected: Gradle reports the project or application class is missing.

- [ ] **Step 3: Add the module and minimal application**

  Copy the dependency conventions from `board-service/build.gradle.kts`, use the shared-common dependency, add Flyway/JPA/Security/Web/Testcontainers dependencies, and register `education-service` in `settings.gradle.kts`. Set the service port to `8115` locally and use `EDUCATION_SERVICE_URI` in the gateway route.

- [ ] **Step 4: Run the boot test to verify it passes**

  Run `./gradlew :education-service:test --tests '*EducationServiceApplicationTest'`.
  Expected: PASS with the service context loading against the test profile.

- [ ] **Step 5: Verify the gateway contract**

  Extend the existing gateway route assertion with `education-service` and run `./gradlew :gateway-service:test`.

### Task 2: Define course and lesson domain behavior

**Files:**
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/Course.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/CourseStatus.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/Lesson.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/LessonContentType.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/LessonStatus.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/Instructor.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/exception/InvalidCourseStateException.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/domain/exception/LessonOrderViolationException.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/domain/CourseTest.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/domain/LessonTest.java`

**Interfaces:**
- `Course.create(...)`, `update(...)`, `publish(...)`, `hide(...)`, and `close(...)` enforce explicit state transitions and update audit/version data.
- `Lesson` validates a positive sequence, supported content type, and required content reference.
- Reorder validation accepts a course lesson set and a requested ordered lesson-id list and rejects foreign, missing, or duplicated IDs.

- [ ] **Step 1: Write failing domain tests**

  Cover `DRAFT -> PUBLISHED`, `PUBLISHED -> HIDDEN`, `HIDDEN -> CLOSED`, invalid transitions, required content validation, and reorder rejection for duplicate or foreign lesson IDs.

- [ ] **Step 2: Run domain tests and observe failure**

  Run `./gradlew :education-service:test --tests '*CourseTest' --tests '*LessonTest'`.
  Expected: compilation failure because the domain types do not exist.

- [ ] **Step 3: Implement the minimal domain model**

  Use immutable identifiers, explicit enums, constructor/factory validation, and methods for each allowed state transition. Do not add framework annotations to domain classes.

- [ ] **Step 4: Run domain tests to green**

  Run the same targeted Gradle command.
  Expected: all domain tests PASS.

- [ ] **Step 5: Run architecture verification**

  Add an ArchUnit test that rejects imports from `org.springframework`, `jakarta.persistence`, and `org.springframework.web` in `github.lms.lemuel.education.domain..`.

### Task 3: Add persistence schema and repositories

**Files:**
- Create: `education-service/src/main/resources/db/migration/V1__education_schema.sql`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/CourseJpaEntity.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/LessonJpaEntity.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/InstructorJpaEntity.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/CourseRepository.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/LessonRepository.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/InstructorRepository.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/CoursePersistenceAdapter.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/persistence/LessonPersistenceAdapter.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/integration/EducationBootIT.java`

**Interfaces:**
- Tables: `education_courses`, `education_lessons`, and `education_instructors`, with foreign keys, status checks, unique `(course_id, sequence)`, audit actor columns, and integer version columns.
- Persistence ports expose course/lesson lookup, paginated course search, save, and atomic lesson reorder without leaking JPA entities into application code.

- [ ] **Step 1: Write repository and migration integration tests**

  Add a Testcontainers PostgreSQL boot test that applies Flyway, validates the mappings, persists one course with lessons, and verifies duplicate lesson sequence values are rejected.

- [ ] **Step 2: Run the integration test and observe failure**

  Run `./gradlew :education-service:test --tests '*EducationBootIT'`.
  Expected: missing migration/entities cause the test to fail.

- [ ] **Step 3: Implement the migration and adapters**

  Map enums as strings, use `@Version`, keep file/member references as strings/IDs, and implement atomic reorder within one transaction. Configure the service database with `EDUCATION_DB_*` environment variables and `ddl-auto=validate`.

- [ ] **Step 4: Run the integration test to green**

  Run `./gradlew :education-service:test --tests '*EducationBootIT'`.
  Expected: Flyway and JPA validation pass.

### Task 4: Implement application use cases and ADMIN API

**Files:**
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/port/in/ManageCourseUseCase.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/port/in/ManageLessonUseCase.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/port/out/CourseStore.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/port/out/LessonStore.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/service/CourseAdminService.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/service/LessonAdminService.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/in/web/AdminEducationController.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/in/web/EducationExceptionHandler.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/in/web/dto/EducationPayloads.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/application/service/CourseAdminServiceTest.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/adapter/in/web/AdminEducationControllerTest.java`

**Interfaces:**
- `GET /admin/education/courses` supports `status`, `query`, `page`, and `size`.
- `POST /admin/education/courses` creates a draft.
- `GET/PUT /admin/education/courses/{courseId}` reads/updates a course.
- `POST /admin/education/courses/{courseId}/publish|hide|close` performs state transitions.
- `POST/PUT/DELETE /admin/education/courses/{courseId}/lessons...` manages lessons.
- `POST /admin/education/courses/{courseId}/lessons/reorder` accepts an ordered list of lesson IDs.
- All mutating endpoints obtain actor identity from the authenticated principal and return `403` to non-ADMIN callers.

- [ ] **Step 1: Write failing service tests**

  Test draft creation, publish validation, invalid transition rejection, optimistic-lock conflict propagation, and lesson reorder ownership validation.

- [ ] **Step 2: Run service tests and observe failure**

  Run `./gradlew :education-service:test --tests '*CourseAdminServiceTest'`.
  Expected: missing use cases and services cause compilation failure.

- [ ] **Step 3: Implement application services**

  Keep transactions in application services, load the aggregate before mutation, call domain methods, save once, and map domain failures to stable error codes.

- [ ] **Step 4: Write failing controller tests**

  Add MockMvc tests for ADMIN success, MANAGER/USER `403`, invalid payload `400`, missing course `404`, and publish response shape.

- [ ] **Step 5: Run controller tests and observe failure**

  Run `./gradlew :education-service:test --tests '*AdminEducationControllerTest'`.
  Expected: missing controller/security wiring causes failure.

- [ ] **Step 6: Implement controller and security matcher**

  Add the routes from the design spec, validation DTOs, exception mapping, and an explicit `/admin/education/** -> ROLE_ADMIN` matcher.

- [ ] **Step 7: Run service and controller tests to green**

  Run `./gradlew :education-service:test --tests '*CourseAdminServiceTest' --tests '*AdminEducationControllerTest'`.

### Task 5: Add Outbox-backed publication event and audit records

**Files:**
- Create: `education-service/src/main/java/github/lms/lemuel/education/application/port/out/EducationEventPublisher.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/event/CoursePublishedEventPublisher.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/event/EducationEventPayload.java`
- Create: `education-service/src/main/java/github/lms/lemuel/education/adapter/out/audit/EducationAuditAdapter.java`
- Modify: `education-service/src/main/java/github/lms/lemuel/education/application/service/CourseAdminService.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/application/service/CoursePublicationEventTest.java`
- Test: `education-service/src/test/java/github/lms/lemuel/education/adapter/out/event/CoursePublishedEventPublisherTest.java`

**Interfaces:**
- Publishing a course saves the course transition, audit record, and outbox row in the same transaction.
- Event type is `CoursePublished`; payload contains `eventId`, `courseId`, `title`, `publishedAt`, `publishedBy`, and `version`.
- The publisher uses the existing shared-common outbox port/entity conventions and never invokes `kafkaTemplate.send()` directly.

- [ ] **Step 1: Write the failing atomic publication test**

  Assert that a successful publish writes one outbox event and that a persistence failure leaves no publication event. Assert event type and required payload fields.

- [ ] **Step 2: Run the test and observe failure**

  Run `./gradlew :education-service:test --tests '*CoursePublicationEventTest' --tests '*CoursePublishedEventPublisherTest'`.
  Expected: the event publisher and outbox integration are missing.

- [ ] **Step 3: Implement the outbox adapter and audit call**

  Reuse the repository's established outbox API, serialize the payload as a decimal-safe JSON object where applicable, and include actor/correlation metadata in the audit record.

- [ ] **Step 4: Run the event tests to green**

  Run the same targeted Gradle command.
  Expected: PASS with no direct Kafka producer usage.

### Task 6: Add the ADMIN frontend course console

**Files:**
- Create: `frontend/src/api/education.ts`
- Create: `frontend/src/pages/system/EducationCourseAdminPage.tsx`
- Create: `frontend/src/__tests__/api/education.test.ts`
- Create: `frontend/src/__tests__/pages/EducationCourseAdminPage.test.tsx`

**Interfaces:**
- API module uses the established admin gateway path `/admin/education/courses`; tests must lock the final path.
- Page supports course list/search/status filter, create/edit draft, lesson editing/reordering, publish/hide/close confirmation, loading, validation, conflict, and error states.
- Page does not expose learner enrollment or progress controls in the first release.

- [ ] **Step 1: Write failing API and page tests**

  Test request paths and methods, render a course list, show an empty state, open edit mode, submit publish, and display a failed-save message.

- [ ] **Step 2: Run frontend tests and observe failure**

  Run `npm test -- --run frontend/src/__tests__/api/education.test.ts frontend/src/__tests__/pages/EducationCourseAdminPage.test.tsx`.
  Expected: modules and page are missing.

- [ ] **Step 3: Implement API client and page**

  Follow the existing `board.ts` and system-admin page patterns, use the shared authenticated HTTP client, and keep state transitions explicit in the UI.

- [ ] **Step 4: Run frontend tests to green**

  Run the same targeted Vitest command.
  Expected: all education API/page tests PASS.

### Task 7: Register ADMIN menu and route

**Files:**
- Create: `order-service/src/main/resources/db/migration/V20260816100000__education_admin_menu.sql`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/data/menuFallback.ts`
- Create or modify: `frontend/src/__tests__/lib/menuFallbackParity.test.ts`
- Create or modify: `frontend/src/__tests__/App.test.tsx`

**Interfaces:**
- Add `교육 관리` as an ADMIN-only menu under the existing system-management area.
- Register only the implemented `/admin/education/courses` route.
- Frontend route uses `AdminOnlyRoute` and `SideNavLayout`, matching the existing sensitive admin pages.
- Backend menu filtering and frontend fallback must agree on label, path, and required role.

- [ ] **Step 1: Write failing menu/route tests**

  Assert the fallback parity entry, ADMIN visibility, non-ADMIN absence, and route rendering for `/admin/education/courses`.

- [ ] **Step 2: Run tests and observe failure**

  Run `npm test -- --run frontend/src/__tests__/lib/menuFallbackParity.test.ts frontend/src/__tests__/App.test.tsx`.
  Expected: no education menu or route is present.

- [ ] **Step 3: Add migration, route, and fallback entry**

  Use the existing menu migration schema and required-role convention. Make the server menu the source of truth and keep the fallback parity-tested for degraded gateway operation.

- [ ] **Step 4: Run frontend and order-service targeted tests**

  Run `npm test -- --run frontend/src/__tests__/lib/menuFallbackParity.test.ts frontend/src/__tests__/App.test.tsx` and `./gradlew :order-service:test --tests '*MenuServiceTest'`.

### Task 8: Verify, document, and hand off

**Files:**
- Modify: `docs/DEVELOPMENT.md` or the service runbook location identified by the repository docs
- Create: `education-service/README.md`
- Test: repository verification commands

- [ ] **Step 1: Document local configuration**

  Document service port, `EDUCATION_DB_*` variables, gateway URI, Flyway ownership, ADMIN-only scope, and the first event contract.

- [ ] **Step 2: Run service verification**

  Run `./gradlew :education-service:test`, `./gradlew :education-service:jacocoTestCoverageVerification`, and `./gradlew :gateway-service:test`.

- [ ] **Step 3: Run frontend verification**

  Run `npm test -- --run` and `npm run build` from `frontend`.

- [ ] **Step 4: Run repository guards**

  Run `git diff --check`, the menu-route harness test `node --test scripts/harness/test/menu-route-gate.test.mjs`, and `node scripts/harness/harness-audit.mjs`.

- [ ] **Step 5: Inspect final scope**

  Run `git status -sb` and review only education-service, gateway/runtime, menu, frontend, and documentation changes. Do not stage or alter the existing unrelated menu-policy worktree changes.
