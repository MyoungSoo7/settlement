# Seed — company-service 사업장 월별 시계열 추이 조회

> **상태: IMPLEMENTED** (2026-07-30 인터뷰 결정화 → 당일 TDD 구현 완료 — 백엔드 `f435c51ab` · 프런트 `9d87df432`)
> Ouroboros Interview Path B(플러그인 폴백 — MCP 질문 생성기 크레딧 부족으로 2회 실패) → 직접 결정화.
> 인터뷰 전문: `.claude/scratch/interview-workforce-history.md` (Q1~Q7 결정 이력 포함).
> 게이트: `:company-service:test` + `jacocoTestCoverageVerification`(LINE 90%) + `HexagonalArchitectureTest` +
> `guard.mjs` + 프런트 vitest/build.

## Goal (한 줄)

**company-service에 사업장 키(사업장명+사업자번호 앞6자리) 고정 월별 시계열을 반환하는 내부 인증
GET `/api/company/workforce/history`를 신설해 — 월 오름차순 원시값(인원·추정연봉·상한 플래그)과
연속 인접 월에만 계산되는 전월 대비 증감(결측 갭·첫 월 null, HALF_UP)을 제공하고 WorkforcePage
상세에 추이 섹션을 추가하되(단월이면 안내 문구), 보간·명칭변경 재연결·랭킹·집단 추이·이벤트 발행은
제외하며 기존 목록·상세 응답은 불변으로 유지한다.**

## 왜 이걸 하는가

업종·지역 비교(직전 시드, IMPLEMENTED)는 **한 달의 횡단면**만 보여준다 — "지금 이 사업장이 집단
대비 어디쯤인가". 월간 스냅샷이 축적되기 시작한 지금, 같은 데이터에서 **종단면**(이 사업장이
커지고 있는가/줄고 있는가)을 꺼낼 수 있다. 직전 시드가 "월별 시계열 추이"를 명시 제외했던 것의
자연스러운 후속이다.

## 범위

| 포함 | 제외 (인터뷰 Q6에서 전부 명시 제외) |
|------|------|
| 신규 GET `/api/company/workforce/history` (JWT ADMIN/MANAGER) | 증감 랭킹 TOP N |
| 월 오름차순 시리즈 + 전월 대비 증감 서버 계산 | 업종/지역 집단 추이 |
| WorkforcePage 상세 추이 섹션 (단월 안내 포함) | 신규/소멸 사업장 탐지 |
| — | Kafka 이벤트 발행 |
| — | 명칭 변경 재연결 휴리스틱 · 결측 월 보간 |

## 핵심 설계 결정 (인터뷰에서 확정)

1. **시리즈 키 = (사업장명, 사업자번호 앞6자리)** — 상세 복합키에서 월을 뺀 2요소. 앞6자리는 단독
   유일이 아니므로 사업장명이 바뀌면 **시리즈가 단절되는 것을 수용**한다(재연결 휴리스틱은 오탐
   위험으로 기각). 조회는 기존 UNIQUE(workplace_name, biz_reg_no_prefix, snapshot_month) 인덱스의
   prefix 스캔 — 신규 마이그레이션 0.
2. **결측 월은 구멍** — 수집을 건너뛴 달은 보간하지 않고 시리즈에서 빠진 채 노출한다. 증감은
   **연속된 인접 월(YearMonth+1)일 때만** 계산하고, 갭을 사이에 둔 두 스냅샷 간에는 null.
3. **순증감만** — 입사·퇴사 gross 분리는 원천 데이터(사업장 합계 스냅샷)로 불가능. 파생값은
   전월 대비 인원 증감(명·%)과 추정연봉 증감(원·%)의 4종.
4. **증감은 서버 계산** — 라운딩 정책(HALF_UP, 비율 2자리·금액 0자리)과 금액 소수 문자열 계약을
   도메인이 보존한다. 프런트 계산으로 미루면 표시 정밀도 규칙이 프런트로 샌다.
5. **신규 엔드포인트** — 기존 목록·상세 응답은 불변(직전 시드 AC-1 정신). history 는 월 파라미터가
   없는 2요소 키 계약으로 상세(3요소)와 구분된다.
6. **금액 wire 는 소수 문자열** — Boot 4 런타임의 HTTP 변환이 Jackson 3 이라 직렬화기 애너테이션이
   무시되는 함정(직전 시드 결함 2) 그대로 적용: DTO 필드 타입 String + `toPlainString()`.

## 수용 기준 (5개)

| AC | 기준 |
|----|------|
| AC-1 | 신규 GET `/api/company/workforce/history?name&bizRegNoPrefix`(JWT ADMIN/MANAGER) — 월 오름차순 시리즈(월·인원·추정연봉 문자열·상한 도달 플래그), 미매칭 404 / 형식 위반 400(기존 `{"message"}` 계약, 검증 순서 name → bizRegNoPrefix) |
| AC-2 | 증감은 연속 인접 월만 — headcountChange(명)·headcountChangeRate(%)·salaryChange(원 문자열)·salaryChangeRate(%), 결측 갭·첫 월 null, HALF_UP(비율 2자리·금액 0자리), 전월 값 0이면 rate null |
| AC-3 | 시리즈 키 (사업장명+앞6자리) 고정 — 명칭 변경 단절·보간·재연결 없음을 테스트로 고정 |
| AC-4 | 단월(길이 1) 시리즈 정상 반환(증감 전부 null) + 화면은 "추이 데이터 1개월" 안내 |
| AC-5 | 기존 목록·상세 응답 불변 + 공통 게이트 + 프런트 vitest/build GREEN |

## 데이터 사실 (인터뷰 [from-code] 확인)

- 현재 적재 스냅샷은 2026-06 **1개월뿐** → AC-4(단월)가 초기 상태의 실경로다.
- 저장 행은 적재 규칙상 headcount>0·고지금액>0 이지만 추정연봉 계산은 방어적으로 null 허용
  (가입자수 0 이력 행이 존재할 수 있음 — 증감도 어느 한쪽 null 이면 null).
- `snapshot_month` 는 엔티티에 "YYYY-MM" 문자열로 저장 — 사전순 정렬 = 시간순 정렬.

## 산출 이력

| 단계 | 결과 |
|------|------|
| Interview | Path B 7문 (가치축→소비형태→범위→시맨틱→파생값→비범위→AC), 전 트랙 닫힘 |
| 수용 가드 | Closer seed_ready · Contrarian/Gap HIGH 0 |
| Restate | goal 한 줄 사용자 승인 (2026-07-30) |
