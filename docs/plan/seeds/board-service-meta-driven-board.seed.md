# Seed — board-service 메타 주도 게시판 as-is 사양

> **상태: CONFIRMED** (2026-08-22 역산) · 정본 데이터: [`board-service-meta-driven-board.seed.yaml`](board-service-meta-driven-board.seed.yaml)
> 자매 문서: [`../prd/board-service.md`](../prd/board-service.md) · 설계 근거 [`../board-service.md`](../board-service.md) · 규칙 `board-domain-rules` 스킬
>
> | 판 | 일자       | 대조 기준             | 비고                                |
> | -- | ---------- | --------------------- | ----------------------------------- |
> | v1 | 2026-08-22 | `develop` `92d25c463` | 최초 결정화 (Phase 1~3 완료 시점)   |
>
> **원칙**: 이 Seed 는 "현행 코드가 실제로 하는 일"의 불변 기술이다. 결함은 교정하지 않고
> Known Issues 로만 기록한다.

## Goal (한 줄)

**board-service 의 메타 주도 게시판 루프(정의 1행 = 게시판 1개 · 정의가 게시글 규칙을 소유 ·
역할 allowlist 인가 · 매직바이트 첨부 검증)의 현행 동작을 실행 가능한 게이트에 매핑된 불변
사양으로 결정화해, 회귀 기준선 · 게시판 추가 시 배포가 필요 없다는 설계 전제의 방어선 ·
면접/포트폴리오 문서로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| `BoardDefinition` 라이프사이클·스킨↔정책 정합 | 프론트 렌더 컴포넌트 내부 |
| 인가 모델(역할 allowlist 4행위) | RBAC `permissions` 테이블(order 소유) |
| 게시글·댓글 상태·가시성·IDOR 대조 | 메뉴 등록(order `POST /admin/menus` 가 한다) |
| 본문 정화(XSS) 경계 | 분류 코드의 의미(공통코드 그룹, 표시용 라벨) |
| 첨부 매직바이트 판정·다운로드 헤더 | 파일 저장소 백업·보존 정책 |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **정의가 게시글의 규칙을 소유한다** — `BoardPost.create`(`BoardPost.java:42`)가 `BoardDefinition`
   을 **인자로 받아** 조립 시점에 닫힘·쓰기 권한·작성자 위조·비밀글 허용·분류 그룹을 한 번에
   검사한다. 응용·웹 계층이 같은 규칙을 다시 검사하지 않는다(재검사는 규칙 이원화).
2. **스킨↔정책 정합은 조립 시점 차단** — `GALLERY` 는 첨부 필수(`BoardDefinition.java:188`),
   `QNA` 는 댓글 필수(`:192`). `create`(`:39`)와 `update`(`:90`)가 **같은 검사**를 지난다 —
   수정에만 검사가 없으면 사후 우회 경로가 된다. 스킨은 4값(LIST·GALLERY·FAQ·QNA)으로 봉인 —
   스킨을 늘리는 일은 데이터 입력이 아니라 프론트 컴포넌트를 만드는 일이다.
3. **인가 = 역할 allowlist 4행위** — `canRead`/`canWrite`/`canComment`/`canManage`
   (`BoardDefinition.java:205-218`). RBAC `permissions` 코드로 판정하지 않는다 — 그 테이블은
   order-service(opslab)에 있고 읽는 순간 DB-per-service 경계가 무너진다.
   `canComment` 는 역할 이전에 **댓글 활성 여부**를 먼저 본다(`:214`).
4. **빈 집합의 의미가 행위마다 다르다** — **읽기가 비면 공개**(비로그인 포함),
   **쓰기·댓글·운영은 비울 수 없다**(익명 쓰기는 스팸 벡터). `BoardAccessPolicy.of`(`:45`)가 강제.
5. **인가는 애그리거트 안에** — `post.edit(actor, definition, ...)`(`BoardPost.java:97`).
   컨트롤러에 두면 어댑터를 하나 더 만들 때 조용히 빠지고 그게 IDOR 이 된다. 주체는 JWT 에서만
   만든다(`BoardActor`·`BoardAuthor`) — 요청 파라미터의 작성자 식별자는 신뢰하지 않는다.
6. **숨김과 삭제는 다른 축이다** — 상태 3값(PUBLISHED·HIDDEN·DELETED). `DELETED` 는 **종단**이며
   숨김·복구·수정이 모두 막힌다(`assertNotDeleted`, `BoardPost.java:181`·`:222`).
   숨김(`:128`)·복구(`:135`)·고정(`:121`)은 **운영 역할만** — 작성자라도 자기 글을 고정할 수 없고,
   숨긴 글은 작성자에게도 보이지 않는다. 삭제(`softDelete`, `:113`)는 작성자의 의사표시다.
7. **본문 형식은 작성 시점 스냅샷** — 정책이 TEXT→HTML 로 바뀌어도 이미 쓴 글의 렌더 방식은 그대로다.
   평문으로 쓴 글이 갑자기 마크업으로 해석되면 깨져 보이거나, 더 나쁘게는 실행된다.
8. **정화는 저장 시점 한 곳** — 작성·수정 두 경로가 모두 `BoardContentSanitizer` 를 지난다.
   판단은 도메인(`BoardContentPolicy.requiresSanitize`), 수행은 어댑터(`SanitizeHtmlPort`).
   화이트리스트(jsoup `Safelist`)만 쓰며 **HTML 만** 정화한다.
9. **첨부는 요청이 주장하는 값을 하나도 믿지 않는다** — 형식은 매직바이트로 판정하고 그 판정값을
   저장해 다운로드 응답에 쓴다. 판정과 선언이 다르면 거절(`AttachmentUpload.java:91`),
   SVG·HTML·XML 은 정책이 허용해도 차단(`ALWAYS_BLOCKED`, `:27`·`:87`).
   저장 파일명은 서버가 만든 UUID — 경로 조작을 막는 확실한 방법은 정화가 아니라 입력을 안 쓰는 것.
10. **순서가 곧 안전** — 판정 → 검증(`post.assertCanAttach`, `BoardPost.java:150`) → 저장 → 행 기록.
    저장 뒤에 거절하면 거절당한 파일이 디스크에 남는다.
11. **키는 불변** — `boardKey` 는 URL 이자 메뉴 행이 가리키는 값이라 `update` 커맨드에 키가 없다.
    2~40자 소문자·숫자·하이픈(`BOARD_KEY_PATTERN`, `BoardDefinition.java:31`), 소문자로 접어
    정규화 후 중복 검사(`:132`) — `Notice` 와 `notice` 가 다른 게시판으로 새지 않게.
12. **삭제는 닫힌 게시판만** — 운영 중 게시판을 한 호출로 지우면 링크와 메뉴 행이 동시에 죽는다.
    비활성화(`:111`)·활성화(`:119`)는 현재 상태와 같으면 예외(무의미한 재적용을 성공으로 보고하지 않는다).
13. **`rehydrate` 는 재검증하지 않는다**(`BoardPost.java:76`) — 정책이 강화되면 기존 게시판
    **조회 자체가 죽기** 때문이다.
14. **가시성은 질의 조건으로 번역한다**(`PostSearchCriteria`) — 페이지를 읽고 자바에서 걸러 내면
    총건수와 페이지 크기가 어긋난다. 목록 페이지 크기 상한 100(`PostListQuery`).

## 경계 — 발행 0 · 소비 0

**Kafka 토픽 없음.** Outbox·컨슈머가 없고 `lemuel_board` 에 그 테이블도 없다. 스캔이
`github.lms.lemuel.board` 로 한정돼 shared-common 의 Outbox·Audit 엔티티를 **의도적으로** 들이지 않는다.

**메뉴 행을 만들지 않는다** — `menus` 는 order-service 소유다. 게시판 생성 후 메뉴 등록은 관리
화면이 기존 `POST /admin/menus` 를 한 번 더 호출한다. 게시판 생성이 곧 전사 네비게이션 변경이 되면
테스트로 만든 게시판·오타 난 이름이 즉시 모두에게 노출된다.

## 응답 규약

| 상황 | 코드 |
| --- | --- |
| 키 중복 | 409 |
| 불변식 위반(스킨↔정책·형식·삭제 가드) | 400 |
| 없는 게시판 | 404 |
| **읽을 수 없는 게시판·게시글** | **404** — 403 은 존재를 알려 줘 키 대입 탐색을 허용한다 |
| 쓰기·수정·삭제 권한 없음 | **403** — 대상의 존재를 이미 아는 주체의 조작이라 감출 것이 없다 |

`@ExceptionHandler(Exception.class)` catch-all 을 두지 않는다 — 시큐리티의 `AccessDeniedException`
까지 삼켜 403 이 500 으로 바뀐다.

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 정의 라이프사이클·스킨↔정책 정합(create/update 동일 검사) | `:board-service:test` 도메인 테스트 |
| AC-2 | 인가 4행위 allowlist, 읽기 공백=공개 / 쓰기·댓글·운영 공백 금지 | `BoardAccessPolicy`·`BoardDefinition` 테스트 |
| AC-3 | 게시글 상태 전이(DELETED 종단)·IDOR 대조·숨김 권한 | `BoardPost` 도메인 테스트 |
| AC-4 | 본문 정화가 작성·수정 두 경로에서 동작 | 정화 테스트 |
| AC-5 | 첨부 매직바이트 판정·ALWAYS_BLOCKED·다운로드 헤더 3종 | 첨부 테스트 |
| AC-6 | 응답 규약(404/403 분리, catch-all 없음) | 컨트롤러·예외 테스트 |
| AC-7 | LINE ≥ 90% | `:board-service:jacocoTestCoverageVerification` |
| AC-8 | 발행 0·소비 0 경계 유지 | `topic-consumer-gate` (board 소유 토픽 0) · `outbox-poller-gate` 대상 밖 |
| AC-9 | 헥사고날 의존 방향 위반 0 | ArchUnit |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1**: `management.server.port` 가 **8115** 로 education-service 의 `server.port` 기본값과 겹친다.
  compose 는 컨테이너 내부 8080 을 써서 무사하지만 **로컬 동시 기동은 나중에 뜬 쪽이 실패**한다.
- **KI-2**: 메뉴 행이 시드가 아니라 런타임에 만들어져 `menu-route-gate` 의 시드 대조 대상이 아니다
  (`ROUTES_WITHOUT_MENU` 에 사유와 함께 등록됨). 즉 **게시판 라우트의 메뉴 존재는 기계로 검증되지 않는다** —
  라우트가 게시판 수만큼 늘지 않는 설계의 대가다.
- **KI-3**: 분류가 공통코드 그룹 코드 **문자열 참조**(`BOARD_CAT_*`)라 cross-DB FK 가 없다.
  order 쪽에서 그룹을 지워도 board 는 모른다 — 표시용 라벨이라 감수한 선택.
- **KI-4**: 작성자 표시명이 작성 시점 마스킹 스냅샷(`ad***`)이라 사용자가 이름을 바꿔도 과거 글은
  그대로다. user 프로젝션을 만들지 않는다는 경계의 대가.
- **KI-5**: 첨부 파일은 파일시스템에 저장되고 **트랜잭션 밖**이다. DB 기록 실패 시 방금 쓴 파일을
  손으로 되돌린다 — 되돌리기 자체가 실패하면 고아 파일이 남는다.
- **KI-6**: 첨부 볼륨(`APP_BOARD_ATTACHMENT_BASE_DIR`)을 마운트하지 않고 컨테이너를 다시 만들면
  **첨부 파일만 사라지고 DB 행은 남아** 다운로드가 404 로 떨어진다.
