---
name: board-domain-rules
description: 메타 주도 게시판 도메인 핵심 규칙 — 정의가 게시글 규칙을 소유(스킨↔정책 정합), 권한은 역할 allowlist(permission 코드 금지·order DB 조회 금지), 읽기 공백=공개/쓰기 공백 금지, 키 불변·닫힌 게시판만 삭제, 메뉴 등록은 board 가 하지 않는다. board-service 로직을 작성·수정·리뷰할 때 로드.
---

# 게시판 도메인 규칙 (board-service)

메타 주도 게시판 플랫폼. `board_definitions` **1행 = 게시판 1개**이고, 프론트의 단일 라우트
`/boards/:boardKey` 가 정의를 읽어 스킨을 바꿔 그린다 — 게시판을 늘리는 데 배포가 필요 없다.
설계 근거 정본은 [`docs/plan/board-service.md`](../../../docs/plan/board-service.md).

## 1. 정의가 게시글의 규칙을 소유한다 (이 서비스의 존재 이유)

`BoardDefinition` 은 데이터 묶음이 아니라 **그 게시판에 쌓일 모든 글의 규칙**이다. 게시글 생성은
정의를 인자로 받아 도메인이 조립 시점에 검사한다.

```java
BoardPost.create(definition, actor, author, title, content, categoryCode, secret, now);
//  ├─ 닫힌 게시판                                   → BoardInvariantViolation
//  ├─ definition.canWrite(actor.role()) 실패        → BoardAccessDenied
//  ├─ actor 와 author 식별자 불일치(작성자 위조)     → BoardAccessDenied
//  ├─ 비밀글인데 게시판이 비밀글 불허               → BoardInvariantViolation
//  └─ 분류 그룹 없는 게시판에 분류 지정              → BoardInvariantViolation
```

응용 서비스나 컨트롤러가 `if (definition.isAttachmentsEnabled() && ...)` 로 다시 검사하면
규칙이 두 곳에 생기고 반드시 어긋난다. **판정은 언제나 도메인이 한다.**

### 스킨 ↔ 정책 정합 (조립 시점 차단)

| 스킨 | 강제 |
| --- | --- |
| `GALLERY` | 첨부 필수 — 썸네일 없는 그리드는 빈 칸만 남는다 |
| `QNA` | 댓글 필수 — 답할 수단 없는 질문 게시판이 된다 |
| `LIST` `FAQ` | 제약 없음 |

`create` 와 `update` 가 **같은 검사**를 통과해야 한다. 수정에만 검사가 없으면 사후 우회 경로가 된다.
`update` 는 검증을 끝낸 뒤 대입한다 — 중간에 던지면 애그리거트가 반쯤 바뀐 채 살아남는다.

## 2. 인가 = 역할 allowlist (permission 코드 금지)

```java
definition.canRead(role) / canWrite(role) / canComment(role) / canManage(role)
```

- **RBAC `permissions` 코드로 판정하지 않는다.** 그 테이블은 order-service(opslab)에 있고, 읽는
  순간 DB-per-service 경계가 무너진다. 역할은 JWT 클레임(`role`)에 이미 실려 온다.
- 빈 집합의 의미가 행위마다 다르다 — **읽기가 비면 공개**(비로그인 포함), **쓰기·댓글·운영은 비울 수 없다**
  (익명 쓰기는 스팸 벡터라 미지원). `BoardAccessPolicy.of` 가 강제한다.
- `canComment` 는 역할 이전에 **댓글 활성 여부**를 먼저 본다 — 댓글이 꺼진 게시판은 역할이 맞아도 false.
- 역할 문자열을 enum 으로 봉인하지 않는다. 역할은 RBAC 테이블의 데이터라 운영 중 늘어난다.
- 글·댓글 수정·삭제는 **JWT 주체(`uid`)와 `author_id` 대조**로 판정한다(IDOR). 요청 파라미터의 작성자
  식별자는 절대 신뢰하지 않는다 — `BoardActor`·`BoardAuthor` 는 웹 어댑터가 JWT 에서만 만든다.
- **인가는 애그리거트 안에 둔다**: `post.edit(actor, definition, ...)`. 컨트롤러에 두면 어댑터를
  하나 더 만들 때(관리 콘솔·배치·내부 API) 조용히 빠지고, 그게 IDOR 이 된다.
- 고정·숨김·복구는 **운영 역할만** — 작성자라도 자기 글을 상단 고정할 수 없다.

## 2-1. 게시글·댓글 규칙 (Phase 2)

- **본문 형식은 작성 시점 스냅샷**. 게시판 정책이 TEXT→HTML 로 바뀌어도 이미 쓴 글의 렌더 방식은
  그대로다 — 평문으로 쓴 글이 갑자기 마크업으로 해석되면 깨져 보이거나, 더 나쁘게는 실행된다.
- **작성자 표시명은 마스킹 스냅샷**(`BoardAuthor.fromSubject` → `ad***`). 원문 이메일을 저장하지 않는다.
- **삭제는 상태 전이**. 글의 `DELETED` 는 종단이다(숨김·복구·수정 불가). 댓글은 `visibleContent()` 가
  자리표시만 돌려주고 원문은 감사용으로 DB 에만 남는다 — 응답 경로로 절대 내보내지 말 것.
- **숨김(HIDDEN)과 삭제(DELETED)를 합치지 말 것**. 숨김은 운영자가 되돌릴 수 있는 조치, 삭제는
  작성자의 의사표시다. 합치면 운영자가 내린 글을 작성자가 되살릴 수 있게 된다.
  숨긴 글은 **작성자에게도** 보이지 않는다(운영 역할만).
- **답글은 1단까지**(`resolveParentId`). 다른 글의 댓글·삭제된 댓글에는 답글을 달 수 없다.
- **가시성은 질의 조건으로 번역**한다(`PostSearchCriteria`) — 페이지를 읽고 자바에서 걸러 내면
  총건수와 페이지 크기가 어긋난다. 판정 기준은 도메인과 같고 번역만 응용 계층이 한다.
- 동적 조건은 **Specification** 으로. `:param IS NULL OR col = :param` JPQL 은 PostgreSQL 에서
  `bytea` 비교 오류를 낸 전력이 있다.
- 목록 페이지 크기는 **상한 100**(`PostListQuery`). 없으면 한 방에 게시판 전체를 덤프할 수 있다.

## 3. 라이프사이클

- **게시판 키(`boardKey`)는 불변**이다. URL 이자 메뉴 행이 가리키는 값이라 바꾸면 이미 나간 링크가
  전부 죽는다. `update` 커맨드에 키가 없는 것은 실수가 아니다.
- 키는 2~40자 소문자·숫자·하이픈, 하이픈으로 시작·끝 금지. 입력은 소문자로 접어 정규화한 뒤 중복 검사한다
  (`Notice` 와 `notice` 가 다른 게시판으로 새지 않게).
- **삭제는 닫힌 게시판만.** 운영 중 게시판을 한 호출로 지우면 링크와 메뉴 행이 동시에 죽고 되돌릴 수 없다.
- 비활성화·활성화는 현재 상태와 같으면 예외(무의미한 재적용을 성공으로 보고하지 않는다).

## 4. 경계 — 이 서비스는 발행 0 · 소비 0

- **Kafka 토픽 없음.** Outbox·컨슈머를 추가하지 말 것. 추가가 필요해 보이면 먼저 설계 문서 §6 을 읽는다.
- **메뉴 행을 만들지 않는다.** `menus` 는 order-service 소유다. 게시판 생성 후 메뉴 등록은
  관리 화면이 기존 `POST /admin/menus` 를 한 번 더 호출한다. 게시판 생성이 곧 전사 네비게이션
  변경이 되면 테스트로 만든 게시판·오타 난 이름이 즉시 모두에게 노출된다.
- **분류는 공통코드 그룹 코드 문자열 참조**(`BOARD_CAT_*`). cross-DB FK 는 불가능하고 필요하지도 않다 —
  분류는 표시용 라벨이다. 게시판별 분류 테이블을 새로 만들지 말 것.
- 작성자 표시명은 **작성 시점 스냅샷**(`author_id` + `author_name`). user 프로젝션을 만들지 않는다.
- 스캔 범위가 `github.lms.lemuel.board` 로 한정돼 있다 — shared-common 빈이 필요하면 `@Import` 필수
  (Outbox·Audit 엔티티는 의도적으로 스캔하지 않는다. lemuel_board 에 그 테이블이 없다).

## 5. 응답 규약

| 상황 | 코드 |
| --- | --- |
| 키 중복 | 409 |
| 불변식 위반(스킨↔정책, 형식, 삭제 가드) | 400 |
| 없는 게시판 | 404 |
| **읽을 수 없는 게시판·게시글** | **404** (403 아님 — 403 은 존재를 알려 줘 키 대입 탐색을 허용한다) |
| 쓰기·수정·삭제 권한 없음 | **403** — 대상의 존재를 이미 아는 주체의 조작이라 감출 것이 없다 |

`@ExceptionHandler(Exception.class)` catch-all 을 두지 말 것 — 스프링 시큐리티의
`AccessDeniedException` 까지 삼켜 403 이 500 으로 바뀐다.

## 6. 안티패턴 (발견 시 지적)

- 도메인 public setter / `@Setter`·`@Data` (OO 게이트가 차단)
- 응용·웹 계층에서 스킨·첨부·역할 규칙 재검사 (규칙 이원화)
- `rehydrate` 경로에서 재검증 — 정책이 강화되면 **기존 게시판 조회 자체가 죽는다**
- `permissions` 테이블·order DB 조회, Kafka 토픽 추가, `menus` 직접 쓰기
- HTML 게시판 본문 sanitize 누락(Phase 3) — 게시판 도입 시 가장 흔한 사고가 XSS
- 첨부 확장자만 믿는 검증 — 매직바이트 검사가 정본(Phase 3)
