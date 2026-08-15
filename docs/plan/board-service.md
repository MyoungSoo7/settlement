# board-service 설계 — 메타 주도 게시판 플랫폼

> 대상: 신규 마이크로서비스 `board-service` (8114/mgmt 8115, `lemuel_board`).
> 상태: **Phase 1 구현 완료**(게시판 정의 CRUD + 관리 화면). Phase 2~4 는 §9 참조.
> 정본: 이 문서 = 설계 근거, 강제 규칙 = `board-domain-rules` 스킬, 기능 명세 = `SPEC.md` §3.17.

---

## 1. 문제 정의

"시스템이 있으면 보통 메뉴·코드·권한·카테고리를 만들 수 있고, CRUD 게시판·이미지 게시판도 만들 수 있다."

이 저장소의 실측 현황은 다음과 같다.

| 기능 | 상태 | 위치 |
| --- | --- | --- |
| 메뉴 | ✅ | `order-service` `menu/`, `menus` 테이블, `/admin/system/menus` |
| 공통코드 | ✅ | `order-service` `commoncode/`, `/admin/system/codes` |
| 권한(RBAC) | ✅ | `order-service` `rbac/`, `/admin/system/rbac` |
| 카테고리 | △ | `order-service` `category/` — 커머스 상품 분류 전용, 범용 아님 |
| 게시판 / 이미지게시판 | ❌ | 저장소 전체 0건 |
| 범용 첨부파일 | ❌ | `LocalFileSystemImageStorageAdapter` 는 product 전용 |

즉 만들 것은 **게시판 하나**다. 그리고 "관리자 페이지에서 게시판을 만들면 화면이 생긴다"가 요구의 핵심이다.

---

## 2. 근본 설계 결정 — 코드 생성이 아니라 메타 주도

### ❌ 스캐폴딩형

게시판마다 테이블·컨트롤러·페이지를 생성한다. 게시판 하나 추가에 마이그레이션 + 배포가 필요하고,
`menu-route-gate.test.mjs` 는 라우트가 소스에 존재해야 통과하므로 런타임 생성과 원천적으로 충돌한다.

### ✅ 메타 주도 런타임

`board_definitions` 1행 = 게시판 1개. 게시글은 공용 테이블에 `board_id` 로 분리하고, 프론트는
`/boards/:boardKey` **단일 라우트**가 정의를 읽어 스킨을 바꿔 그린다.

- 배포 없이 게시판을 무한히 만들 수 있다.
- 라우트가 1개로 고정이라 메뉴↔라우트 정합 게이트가 깨지지 않는다.
- **"CRUD 게시판"과 "이미지 게시판"은 별개 도메인이 아니라 같은 도메인의 두 스킨**이다.
  이미지 게시판 = `skin=GALLERY` + `attachmentsEnabled` + `대표 이미지 1장 필수` 라는 정책 조합일 뿐이다.

스킨은 enum 으로 봉인한다(`MenuArea` 와 같은 이유 — 스킨을 늘리는 일은 데이터 입력이 아니라
프론트 컴포넌트를 새로 만드는 일이다).

| 스킨 | 렌더 | 대표 용도 |
| --- | --- | --- |
| `LIST` | 제목 목록 + 페이징 | 공지사항, 자료실 |
| `GALLERY` | 썸네일 그리드 | 이미지 게시판, 포토 갤러리 |
| `FAQ` | 아코디언 | FAQ |
| `QNA` | 질문 + 답변 1:N, 비밀글 | 문의 |

---

## 3. 경계 결정 — 왜 별도 서비스인가

첫 초안은 `order-service` 안의 `board/` 패키지였다. 근거는 "게시판별 권한을 `permissions` 코드로
관리하면 `role_permissions` 조인이 필요하고, 그 테이블은 opslab 에 있다"였다. **이 근거는
권한 모델을 바꾸면 사라진다.** 실측:

- JWT 클레임은 `role`(단일 문자열) + `uid` 뿐이다 (`JwtUtil.generateToken`).
- 메뉴 필터링만 order DB 의 rbac 테이블을 읽는다 (`RolePermissionCodesAdapter`).

그래서 아래 4개 결정으로 **order 의존을 0 으로 만들고** 별도 서비스로 분리한다.

| # | 결정 | 없애는 의존 |
| --- | --- | --- |
| 1 | 정의·글·댓글·첨부를 **모두** board-service 에 둔다 | 도메인 불변식(정의가 글의 규칙을 강제)이 경계를 넘지 않는다. 쪼개면 분산 불변식이 되어 최악 |
| 2 | 게시판별 접근 제어 = **역할 allowlist**(`read_roles='ADMIN,MANAGER,USER'`) | RBAC 프로젝션 불필요 — JWT `role` 만으로 판정 |
| 3 | 작성자 표시명을 **작성 시점 스냅샷**(`author_id` + `author_name`) | user 프로젝션 불필요. 게시판은 "그때 그 이름"이 오히려 올바른 의미론 |
| 4 | 메뉴 행은 **관리자가 기존 `POST /admin/menus` 로 연결**(§6) | cross-DB write 0, Kafka 토픽 0 |

결과: **발행 0 · 소비 0 · 타 서비스 의존 0** 의 완전 독립 서비스. `financial`/`economics`/`market`
같은 공개 위성과 달리 쓰기가 있으므로 shared-common JWT 는 쓰되, Outbox 는 쓰지 않는다.

### 분리를 정당화하는 근거

1. **게시판은 커머스가 아니다.** order-service 는 이미 15개 도메인을 안고 있고, board 는 order 의
   어떤 애그리거트도 참조하지 않는다.
2. **부하 특성이 다르다.** 첨부 바이너리 I/O + 공지 브로드캐스트성 읽기 폭주는 주문·결제와 다른 축이다.
3. 이 저장소의 기준(DB-per-service, 코드·DB 직접 의존 0)을 그대로 충족한다.

### 분리의 실비용 (정직하게)

- 게시판 비활성화 ↔ 메뉴 행 정합을 **FK 하나로 못 막는다.** 단일 서비스였다면
  `ON DELETE CASCADE` 로 끝날 일이 cross-DB 라 런타임 대조(§6)로 내려간다.
- 신규 서비스 배선 1식(DB·Flyway·gateway·nginx·Dockerfile·compose·CI·JaCoCo 90%·문서 로스터).

---

## 4. 도메인 모델

```
board/domain/
├── BoardDefinition          # 게시판 그 자체 (Phase 1)
├── BoardSkin                # LIST | GALLERY | FAQ | QNA
├── BoardContentFormat       # TEXT | MARKDOWN | HTML
├── BoardAccessPolicy        # 역할 allowlist 4종 (read/write/comment/manage) — VO
├── BoardAttachmentPolicy    # 허용 여부·최대 개수·최대 크기·확장자 — VO
├── BoardPost                # Phase 2
├── BoardComment             # Phase 2
├── BoardAttachment          # Phase 3
└── exception/
    ├── BoardInvariantViolationException
    ├── BoardNotFoundException
    └── DuplicateBoardKeyException
```

### 핵심: 정의가 게시글의 규칙을 소유한다

```java
// 애플리케이션 서비스가 if 로 검사하는 게 아니라, 도메인이 조립 시점에 막는다 (Phase 2)
BoardPost.create(definition, authorId, authorName, title, content, attachments);
//  ├─ definition 이 첨부를 불허하는데 첨부가 있음        → 예외
//  ├─ attachments.size() > definition 최대 개수          → 예외
//  ├─ skin == GALLERY 인데 대표 이미지 없음              → 예외
//  └─ secret 요청인데 definition 이 비밀글 불허          → 예외
```

이렇게 하면 "이미지 없는 이미지게시판 글" 같은 깨진 상태가 애초에 만들어지지 않는다.
`BoardDefinition` 을 인자로 받는 것이 이 설계의 전부다 — 정의와 글이 같은 서비스에 있어야 하는 이유이기도 하다(§3-1).

### 분류(카테고리)는 새 테이블을 만들지 않는다

`BoardDefinition.categoryGroupCode` 가 **order-service 의 공통코드 그룹 코드 문자열**을 들고 있다
(예: `BOARD_CAT_NOTICE`). FK 가 아니라 **약결합 문자열 참조**다 — cross-DB FK 는 불가능하고,
필요하지도 않다. 게시판별 분류 관리 화면을 따로 만들지 않고 이미 있는 공통코드 화면을 재사용한다.

> 대가: 공통코드 그룹이 지워져도 board 는 모른다. 분류는 표시용 라벨이지 회계 값이 아니므로
> 라벨이 코드값으로 떨어지는 정도의 열화만 발생한다 — 감수한다.

### 왜 Phase 1 이 `BoardDefinition` 단독인가

`BoardDefinition` 은 게시글의 **모든 규칙을 담는 그릇**이다. 글을 먼저 만들면 규칙 없는 글이
쌓이고, 나중에 정의를 붙일 때 기존 데이터가 새 불변식을 위반한다. 그릇을 먼저 봉인한다.

---

## 5. 스키마

```sql
board_definitions(
  id BIGSERIAL PK,
  board_key VARCHAR(40) UNIQUE,              -- URL 세그먼트. 소문자·숫자·하이픈만
  name VARCHAR(100), description VARCHAR(300),
  skin VARCHAR(10),                          -- LIST|GALLERY|FAQ|QNA
  content_format VARCHAR(10),                -- TEXT|MARKDOWN|HTML
  category_group_code VARCHAR(40) NULL,      -- order 공통코드 그룹(약결합)
  comments_enabled BOOLEAN, secret_enabled BOOLEAN,
  attachments_enabled BOOLEAN,
  max_attachment_count INT, max_attachment_size_kb INT,
  allowed_extensions VARCHAR(200),           -- 'jpg,png,webp' — 비면 정책 기본값
  read_roles VARCHAR(100),                   -- NULL = 공개(비로그인 포함)
  write_roles VARCHAR(100), comment_roles VARCHAR(100), manage_roles VARCHAR(100),
  active BOOLEAN, created_at, updated_at TIMESTAMPTZ)

-- Phase 2~3
board_posts(id, board_id FK, category_code, title, content, content_format,
            author_id, author_name, pinned, secret, status, view_count, ...)
board_comments(id, post_id FK, parent_id, author_id, author_name, content, status, ...)
board_attachments(id, post_id FK, kind, original_name, storage_path,
                  content_type, size_bytes, width, height, thumbnail_path, sort_order)
```

핵심 인덱스(Phase 2): `board_posts(board_id, status, pinned DESC, created_at DESC)` — 목록 조회가
항상 이 순서라 정렬까지 인덱스로 흡수된다.

시간 컬럼은 `TIMESTAMPTZ` + 도메인 `OffsetDateTime`(UTC) 로 통일한다(DataStandard N1).

---

## 6. 메뉴 연결 — 이벤트가 아니라 화면에서

`POST /admin/menus` 는 **이미 존재한다**(`AdminMenuController`, path·area·requiredRole 지정 가능).
따라서 "수동 연결"에 필요한 백엔드 코드는 0줄이다.

| | 자동(이벤트) | 수동(채택) |
| --- | --- | --- |
| 신규 코드 | Outbox + 토픽 카탈로그 + 계약 스키마 + 양방향 계약 테스트 + order 컨슈머 + `menus.source` 컬럼 | 0 |
| board-service 성격 | 발행 서비스 | **완전 독립** |
| 일관성 | Eventual (실패 시 무기한) | 관리자 조작 즉시 |
| 게시판 삭제 시 | 메뉴 자동 제거 | 고아 메뉴 발생 가능 → 대조 배지로 대응 |
| 빌드 시점 정합 게이트 | **cross-DB 라 불가** | 동일 |

자동을 버린 이유는 세 가지다.

1. **자동화해도 실제 노동이 안 준다.** 메뉴 붙이기의 진짜 일은 *어느 그룹 밑에, 몇 번째로, 어떤
   아이콘으로, 어떤 역할에게* 인데 board-service 는 이걸 알 수 없다. 자동 등록은 항상 기본 위치에
   떨어뜨리고 관리자는 결국 메뉴 화면에서 옮긴다.
2. **빈도가 손익분기에 못 미친다.** 게시판은 시스템 수명 전체에서 5~20개 수준이다.
3. **자동 등록은 위험하다.** 게시판을 만드는 순간 전사 네비게이션이 바뀐다 — 테스트로 만든
   게시판, 오타 난 이름이 즉시 모두에게 노출된다. 수동이면 "만들고 → 채우고 → 올린다"는
   스테이징이 자연스럽게 생기고, `SYSTEM_BOARD_MANAGE` 와 `SYSTEM_MENU_MANAGE` 를 다른 사람에게
   줄 수 있다.

### 채택안: 하이브리드 (자동화를 백엔드가 아니라 화면에서)

```
BoardAdminPage 에서 게시판 생성
  → "메뉴에 추가" 액션 (부모 그룹·정렬·아이콘·역할 선택, path 는 /boards/{key} 로 자동)
  → 프론트가 기존 POST /admin/menus 를 한 번 더 호출
```

고아 메뉴 대응: 관리 화면이 게시판 목록과 `/admin/menus/flat` 을 **각각 호출해 프론트에서 대조**해
"메뉴 없는 게시판 / 링크 끊긴 메뉴"를 배지로 표시한다(cross-DB 조인이 아니라 화면단 대조).

이 결정은 되돌리기 쉽다 — 나중에 생성이 잦아지면 이벤트로 승격하면 되고, 반대 방향(토픽·계약·
컨슈머 회수)이 훨씬 비싸다.

---

## 7. 인가 모델

게시판별 권한은 **역할 allowlist 문자열**이다. permission 코드가 아니다(§3-2).

```java
definition.canRead(role)     // read_roles == null 이면 비로그인 포함 공개
definition.canWrite(role)
definition.canComment(role)
definition.canManage(role)   // 게시판 단위 운영(고정·숨김) — 게시판 자체 CRUD 와 다름
```

- 게시판 **정의 CRUD**(`/admin/boards/**`)는 `SYSTEM_BOARD_MANAGE` 가 아니라 **ADMIN 역할**로 막는다.
  permission 코드 판정은 order DB 를 읽어야 하므로 의존이 되살아난다. RBAC 시드에
  `SYSTEM_BOARD_MANAGE` 코드를 넣는 것은 메뉴 노출 필터링용이며, 실제 인가는 역할이 한다.
- 글 수정·삭제 권한은 Phase 2 에서 **JWT 주체(`uid`)와 `author_id` 대조**로 판정한다(IDOR 가드레일).
  요청 파라미터의 작성자 식별자는 절대 신뢰하지 않는다.

---

## 8. 첨부·이미지 (Phase 3 선설계)

`product` 의 `LocalFileSystemImageStorageAdapter` 를 **재사용하지 않는다** — 도메인 간 어댑터 공유는
헥사고날 위반이다. `board/application/port/out/StoreAttachmentPort` 를 새로 두고 자체 어댑터를
구현한다(추후 S3 전환은 포트 교체로 끝난다).

보안 필수 항목:
- 확장자 화이트리스트 + **매직바이트 검증**(확장자만 믿으면 안 된다)
- SVG 업로드 차단 또는 sanitize(스크립트 실행 벡터)
- 저장 파일명은 서버 생성 UUID — 경로 traversal 원천 차단
- `content_format=HTML` 게시판은 **서버측 sanitize 필수**(게시판 도입 시 가장 흔한 사고가 XSS)

---

## 9. Phase 로드맵

| Phase | 범위 | DoD |
| --- | --- | --- |
| **1** ✅ | 서비스 골격 + `BoardDefinition` 도메인·CRUD·관리 화면. 게시판을 만들 수는 있으나 글은 없다 | `:board-service:test` + JaCoCo LINE 90%, 3층(직접·gateway·nginx) 200, `harness-audit` 통과 |
| 2 | `BoardPost`/`BoardComment` + LIST 스킨 + 인가 정책 | IDOR 테스트, 역할 allowlist 매트릭스 테스트 |
| 3 | 첨부·이미지 + GALLERY 스킨 | 매직바이트·traversal·XSS 테스트 |
| 4 | FAQ/QNA 스킨 + 메뉴 대조 배지 + 검색 | 고아 메뉴 대조 화면 |

---

## 10. 열어둔 것

- **정의 변경의 소급 효과**: 첨부 허용 게시판을 나중에 불허로 바꾸면 기존 글의 첨부는 어떻게 되는가.
  Phase 1 결론 — **기존 데이터는 건드리지 않고 신규 작성만 막는다**(정책은 미래를 향한다). 스킨을
  `LIST → GALLERY` 로 바꾸는 것은 기존 글에 대표 이미지가 없을 수 있으므로 Phase 3 에서 전환 검사를 넣는다.
- **게시판 삭제**: 글이 있는 게시판의 hard delete 는 금지하고 `active=false` 만 허용한다(Phase 1 구현).
- **다국어**: 현재 범위 밖.
