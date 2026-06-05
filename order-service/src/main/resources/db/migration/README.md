# Flyway Migration 컨벤션 — settlement order-service

## 📜 명명 규칙

### V1 ~ V47 (정수, *legacy*)
*이미 운영에 적용된 정수 번호 마이그레이션*. 변경하지 않는다 (운영 DB 의 `flyway_schema_history` 에 기록 보존).

### V20260606003307__ 이후 (*timestamp*, 권장)
*신규 마이그레이션은 timestamp 명명* 으로 작성.

```
V{YYYYMMDDhhmmss}__{snake_case_summary}.sql

예시:
V20260606003307__add_version_to_payouts.sql
V20260607143200__add_user_status_history.sql
```

### 왜 timestamp 인가
- **병렬 PR 시 충돌 회피** — 두 개발자가 *각자 V48 으로 작성* → merge 시 *Flyway 가 거부* (같은 번호). timestamp 면 *충돌 없음* (초 단위까지 일치 어렵)
- **순서 자명** — *작성 시점* 이 곧 *적용 순서*
- **Flyway 정렬 호환** — V20260606... 은 정수 비교에서 큰 값 → V47 이후 자동 실행

### 충돌 발생 시
*과거 사례* (2026-06-06): PR #94 (V47__init_shedlock) 과 PR #96 (V47__add_version_to_payouts) 가 *각자 main 으로 merge* → 같은 V47 두 개 → 새 pod 시작 시 `FlywayException: Found more than one migration with version 47`.

해결:
1. *후행 PR* 의 마이그레이션을 timestamp 형식으로 rename
2. 운영 `flyway_schema_history` 에 기록된 V47 은 *그대로* (=`V47__init_shedlock.sql`)
3. rename 된 마이그레이션은 *처음 실행되는 것* 처럼 적용됨

### 새 마이그레이션 생성 — 빠른 명령
```bash
# 현재 시각 timestamp 생성
TS=$(date +%Y%m%d%H%M%S)
SUMMARY="add_user_status_column"
touch "order-service/src/main/resources/db/migration/V${TS}__${SUMMARY}.sql"
```

## 🔒 정책 요약
- 정수 번호 — V47 이 *마지막*, 새 PR 에선 사용 금지
- timestamp 명명 — *오늘부터 모든 신규 마이그레이션*
- 충돌 발견 시 — *후행 PR* 이 rename
- 운영 DB 의 `flyway_schema_history` *직접 수정 절대 금지* (Flyway 가 관리)

## 📚 참고
- [Flyway naming convention](https://documentation.red-gate.com/fd/migrations-271583317.html)
- 본 프로젝트 *2026-06-06 컨벤션 전환 이전* 의 V1~V47 은 *영구 유지*.
