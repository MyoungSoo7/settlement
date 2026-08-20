# Education Service Design

## Goal

교육 과정과 강의 콘텐츠를 커뮤니티 게시판과 분리된 `education-service`에서 관리한다. 1차 릴리스는 ADMIN 전용 교육 콘텐츠 관리에 집중하고, 수강·진도·결제·추천 기능은 후속 확장을 위해 경계만 열어 둔다.

## Scope

### In scope

- 교육 과정 생성·조회·수정·비공개·게시·종료
- 과정에 속한 차시 생성·조회·수정·삭제·순서 변경
- 차시 콘텐츠 타입 관리
  - 영상 URL
  - 문서/파일
  - 외부 링크
- 과정 썸네일과 콘텐츠 파일 메타데이터 관리
- ADMIN 전용 관리자 API와 관리자 화면
- 과정 게시 이벤트 발행
- 교육 서비스 전용 데이터베이스와 마이그레이션

### Out of scope for the first release

- 결제·주문 처리
- 수강 신청과 수강권 발급
- 학습 진도·완료율 계산
- 동영상 스트리밍/트랜스코딩
- 추천·개인화
- 학습자용 교육 화면
- 교육 수료증

## Service Boundary

`education-service` owns course, lesson, content, instructor, and publication state. It must not read another service's database directly.

- 회원 원본: member/order-service가 소유한다. 교육 서비스는 필요한 경우 `memberId`만 저장한다.
- 결제 원본: order/payment 영역이 소유한다. 유료 교육은 후속 이벤트 연계로 처리한다.
- 파일 원본: 기존 파일 저장·보안 정책을 재사용하되, 교육 콘텐츠 메타데이터는 education-service가 소유한다.
- 권한 원본: 현재 ADMIN/RBAC 정책을 따른다.
- 알림: 교육 이벤트를 발행하고 알림 처리는 해당 소비자가 담당한다.

## Domain Model

### Course

- `courseId`
- `title`
- `description`
- `thumbnailFileId`
- `status`: `DRAFT`, `PUBLISHED`, `HIDDEN`, `CLOSED`
- `publishedAt`
- `closedAt`
- `createdBy`, `updatedBy`
- `createdAt`, `updatedAt`
- `version`

Course publication is an explicit state transition. A published course cannot be silently overwritten into an inconsistent state; changes must use optimistic locking and audit metadata.

### Lesson

- `lessonId`
- `courseId`
- `title`
- `description`
- `sequence`
- `contentType`: `VIDEO`, `DOCUMENT`, `EXTERNAL_LINK`
- `contentRef`
- `required`
- `status`: `ACTIVE`, `HIDDEN`
- `createdBy`, `updatedBy`
- `createdAt`, `updatedAt`
- `version`

Lesson ordering is unique within a course. Reordering must be atomic and must not leave duplicate sequence values.

### Instructor

The first release stores instructor display metadata owned by education-service. If a later member integration is needed, the record can retain an optional external `memberId` without making member data a local source of truth.

## API Direction

The first administrative API is scoped under `/admin/education` and is protected by `ROLE_ADMIN`.

- `GET /admin/education/courses`
- `POST /admin/education/courses`
- `GET /admin/education/courses/{courseId}`
- `PUT /admin/education/courses/{courseId}`
- `POST /admin/education/courses/{courseId}/publish`
- `POST /admin/education/courses/{courseId}/hide`
- `POST /admin/education/courses/{courseId}/close`
- `POST /admin/education/courses/{courseId}/lessons`
- `PUT /admin/education/courses/{courseId}/lessons/{lessonId}`
- `DELETE /admin/education/courses/{courseId}/lessons/{lessonId}`
- `POST /admin/education/courses/{courseId}/lessons/reorder`

List endpoints must support status filtering, title search, pagination, and stable ordering. Mutating endpoints must return validation errors without partial updates.

## Events

The service publishes through the existing outbox pattern.

### `CoursePublished`

Payload minimum:

- `eventId`
- `courseId`
- `title`
- `publishedAt`
- `publishedBy`
- `version`

Consumers must be idempotent using the repository's processed-event convention. The education service must not publish directly to Kafka outside the outbox boundary.

## Authorization and Audit

- First release menu visibility: `ADMIN` only.
- API authorization: backend security matcher must independently enforce `ROLE_ADMIN`.
- Course publish, hide, close, lesson reorder, and deletion are audited.
- Audit records must contain actor, action, target, result, and correlation/request ID; sensitive file or member data must not be logged in clear text.

## Menu

Add one ADMIN-only system menu group:

- `교육 관리` — `/admin/education/courses`
  - 과정 관리
  - 강의·차시 관리
  - 연자 관리
  - 수강 현황 (initially a disabled/future entry unless an API exists)

Only implemented routes should be enabled in the first migration. Future menu entries must not be exposed as broken links.

## Failure and Consistency Rules

- Course state transitions reject invalid source states with a domain error.
- Optimistic-lock conflicts return a conflict response and preserve the existing record.
- Reordering validates that all referenced lessons belong to the requested course.
- File references are validated before publishing; a course cannot be published with an invalid required content reference.
- Event publication is retried by the outbox worker and remains observable through existing operation tooling.

## Testing Requirements

- Domain tests for valid and invalid course state transitions.
- Domain tests for lesson sequence uniqueness and reorder validation.
- Controller tests for ADMIN authorization and validation responses.
- Repository/integration tests for migrations and optimistic locking.
- Outbox test proving `CoursePublished` is recorded atomically with publication.
- Frontend tests for course list, edit, publish, hide, and failed-save states.
- Existing order, settlement, board, operation, and frontend suites remain green.

## Phased Delivery

1. Create service skeleton, independent schema, and domain model.
2. Implement course/lesson administration with tests first.
3. Add outbox event and operation observability.
4. Add ADMIN menu and frontend routes.
5. Verify full repository suites and migration guards.
6. Design the next bounded context: member CRM and education enrollment.

## Decisions Deferred

- Whether video files are stored internally or by an external media provider.
- Whether instructor identity is linked to member-service.
- Paid course entitlement and refund behavior.
- Learner-facing UX and progress tracking.
- Search index technology.
