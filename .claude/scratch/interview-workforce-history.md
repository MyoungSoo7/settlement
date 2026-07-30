# Interview 결과 — company-service 사업장 월별 시계열 추이 (Path B, 2026-07-30)

> Ouroboros MCP 질문 생성기가 Anthropic 크레딧 부족(`Credit balance is too low`)으로 2회 연속 실패해
> Path B(플러그인 폴백)로 진행. MCP 세션 미저장 — 이 파일이 유일한 기록.
> (MCP 세션 ID `interview_20260730_070717` 은 시작만 되고 질문 생성 실패 상태로 남음)

## goal (Restate 승인됨)

company-service에 사업장 키(사업장명+사업자번호 앞6자리) 고정 월별 시계열을 반환하는 공개
GET `/api/company/workforce/history`를 신설해 — 월 오름차순 원시값(인원·추정연봉·상한 플래그)과
연속 인접 월에만 계산되는 전월 대비 증감(결측 갭·첫 월 null, HALF_UP)을 제공하고 WorkforcePage
상세에 추이 섹션을 추가하되(단월이면 안내 문구), 보간·명칭변경 재연결·랭킹·집단 추이·이벤트 발행은
제외하며 기존 목록·상세 응답은 불변으로 유지한다.

## 결정 이력 (Q&A 7문)

| Q | 질문 | 사용자 결정 |
|---|------|-------------|
| Q1 | 가치 축 | **월별 시계열 추이** (신규/소멸 탐지·마스터 링크·거시 대시보드 기각) |
| Q2 | 소비 형태 | **사업장 단건 추이만** (랭킹·집단 추이 기각) |
| Q3 | API·화면 범위 | **신규 /history 엔드포인트 + WorkforcePage 추이 섹션** (기존 상세 응답 확장 기각 — 응답 불변 원칙) |
| Q4 | 시맨틱 | **명칭 변경 시 시리즈 단절 수용 · 결측 월 보간 금지(구멍 표시) · 순증감만**(입·퇴사 gross 분리는 데이터 한계로 불가) |
| Q5 | 파생값 | **전월 대비 증감(명·%·원·%)까지 서버 계산** — 라운딩 정책(HALF_UP·금액 문자열 계약) 도메인 보존, 결측 갭 사이 증감 null |
| Q6 | 비범위 | **전부 제외**: 증감 랭킹 TOP N · 업종/지역 집단 추이 · 신규/소멸 탐지 · Kafka 이벤트 발행 · 명칭 변경 재연결 휴리스틱 |
| Q7 | AC | 아래 5개 확정 |

## acceptance criteria (확정)

1. **AC-1**: 신규 GET `/api/company/workforce/history?name&bizRegNoPrefix`(공개, 월 파라미터 없는 2요소 키) —
   월 오름차순 시리즈(월·인원·추정연봉 **소수 문자열**·상한 도달 플래그), 미매칭 404 / 형식 위반 400
   (기존 `{"message"}` 오류 계약, 검증 순서 name → bizRegNoPrefix)
2. **AC-2**: 증감은 연속된 인접 월(YearMonth+1)만 계산 — headcount(명·%), 추정연봉(원 문자열·%),
   결측 갭·첫 월은 null, HALF_UP(비율 2자리·금액 0자리)
3. **AC-3**: 시리즈 키 (사업장명+앞6자리) 고정 — 명칭 변경 단절·보간·재연결 없음을 테스트로 고정
4. **AC-4**: 단월(길이 1) 시리즈 정상 반환(증감 전부 null) + 화면은 "추이 데이터 1개월" 안내
5. **AC-5**: 기존 목록·상세 응답 불변 + 공통 게이트(`:company-service:test`·JaCoCo LINE 90%·
   HexagonalArchitectureTest·guard.mjs) + 프런트 vitest/build GREEN

## 인터뷰 중 확인된 코드·데이터 사실 (`[from-code]`)

- 시계열 동일성 키는 상세 복합키(사업장명+앞6+기준월)에서 월을 뺀 2요소 — 앞6자리는 단독 유일 아님
- 현재 적재 스냅샷은 2026-06 **1개월뿐** → AC-4(단월 성립)가 초기 상태의 실경로
- `company_workforce` 유니크 제약이 (사업장명, 앞6, 월) 순이면 history 조회는 prefix 스캔으로 충분 — 구현 시 확인
- 적재 규칙상 저장 행은 headcount>0·고지금액>0 이라 추정연봉 null 희박하나 방어적으로 null 허용
- 추정연봉·금액 wire 는 소수 문자열 계약(Boot 4 Jackson 3 — 직전 시드 결함 2에서 확정)

## Seed-ready 수용 가드 (3관점, 로컬)

- Closer: goal·제약·비범위·검증 가능 AC 완비 → seed_ready
- Contrarian·Gap hunter: HIGH 없음 (인접 월 정의 명확·키 계약 구분·성능은 기존 제약 활용)

## 다음 단계

`ooo seed` 로 결정화 → 다만 MCP 크레딧 이슈가 지속되면 직전 시드와 동형으로
`docs/seeds/company-service-workforce-history.seed.md` 직접 작성(TDD 구현 경로).
