# trusted-ceo-agent — 프로젝트 가이드 + 보완 라운드 결과 보고

삼일PwC "Trusted CEO Agent" 과제 제출물. 기업의 회계·재무·공시 데이터 행간에서 CEO가 놓친
경영리스크를 역추출해 서명용 브리핑으로 전달하는 Codex/Claude 플러그인이다.

- **사용자는 CEO 한 사람** — 산출물은 서명용 브리핑 하나로 수렴한다.
- **재사용 진단 프레임워크** — 정답지(신호·근거 수치)를 코드에 하드코딩하지 않고 분석 대상
  데이터에서 매번 파생 계산한다. 에이전트와 채점기가 같은 파생 엔진을 공유한다.
- **2단계 진단** — 기본 모드는 기업명만으로(DART·ECOS API), 내부 CSV(`--data-dir`)는 상세 축
  (거래처 집중·원가 왜곡·INV-8 공시 대사)을 여는 옵션.

## 핵심 명령어 (모두 `submission/` 기준, zero-dependency Node 22+)

```bash
# 환경 진단 (설치 확인 — Node·키·오프라인 셀프테스트·에이전트 감지·MCP 배선, 네트워크 0)
node src/bin/doctor.mjs [--json]

# 통합 파이프라인 (고객 접수 → 브리핑 채점 완주 — 에이전트 자동 감지, 미감지 시 프롬프트 폴백)
node src/bin/ceo-consulting-pipeline.mjs --company 삼성전자 --business-number 124-81-00998 [--data-dir <내부CSV>] [--judge] [--agent none]

# 진단 (제품 진입점)
node src/bin/diagnose-company.mjs --company 삼성전자                    # 기본 모드 (API-only)
node src/bin/diagnose-company.mjs --company 삼성전자 --data-dir <내부CSV> --dart-unit-scale 1000000  # 상세
node src/bin/diagnose-company.mjs ... --preset commerce --docs-dir <문서폴더> --json  # 프리셋·비정형·패킷
node src/bin/diagnose-company.mjs ... --with-news --with-market                       # 뉴스 축 + 시장 축(E6·E7, KRX_API_KEY)

# 엔게이지먼트 사이클 (브리핑 이후 반복 컨설팅 — ceo-engagement-cycle 스킬이 관리자)
node src/bin/engagement-cycle.mjs init --from <파이프라인 산출폴더>       # 권고 조치 → 추적 액션 파생
node src/bin/engagement-cycle.mjs status|note|delta|advance --engagement <폴더>  # 이행→델타→회고 상태머신

# 분기 브리핑 배치 (목록 일괄 파이프라인 완주 → EVAL PASS 만 company-service 문서함 업로드)
node src/bin/quarterly-briefing-batch.mjs --period 2026Q2 [--companies <목록JSON>] [--only <기업명>] \
  [--resume] [--concurrency N --delay-ms ms] [--register <base URL>] [--no-upload] [--judge] \
  [--escalate-signals N]  # 2단 토큰 절약: 1차 전량 로컬 브리핑(LLM 0) → 재무 신호(E1~E4·E8) N종+ 기업만 LLM 승격
node src/bin/build-briefing-universe.mjs [--markets KOSPI,KOSDAQ] [--limit N] [--dry-run]  # 상장사 전체 배치 목록 생성 (DART_API_KEY)

# 저수준 도구 (상세 모드 구성요소)
node src/bin/verify-books.mjs   --data-dir <폴더> [--dart-corp-code 8자리 --dart-unit-scale N]  # 게이트+INV-8
node src/bin/detect-signals.mjs --data-dir <폴더> [--preset name]                                # 내부 신호 S1~S4

# 채점 (1차 규칙 = 결정론 PASS/FAIL, 2차 Judge = LLM advisory)
node src/test/briefing-eval.mjs [--data-dir <폴더> | --signals-file <packet.json>] [--judge] briefing.md
node src/test/briefing-eval.mjs --self-test

# 검증 인프라
node src/bin/demo-e2e.mjs [--data-dir <폴더>] [--judge] [--agent none|"<cmd>"]   # 원커맨드 E2E 데모
node src/bin/calibrate.mjs [--top N] [--preset name] [--json]                    # 임계값 발화율 측정 (라이브)
node src/test/judge-smoke.mjs                                                    # Judge 라이브 회귀

# 전체 테스트 + 커버리지 게이트 90%
node --test --experimental-test-coverage --test-coverage-lines=90 \
  --test-coverage-include='src/common/**' --test-coverage-include='src/dart/**' \
  --test-coverage-include='src/ecos/**'   --test-coverage-include='src/krx/**' \
  --test-coverage-include='src/mcp/**' \
  --test-coverage-include='src/naver/**'  --test-coverage-include='src/registry/**' \
  --test-coverage-include='src/bin/**' --test-coverage-include='src/test/briefing-eval.mjs' \
  src/test/unit/*.test.mjs
```

## 검증 체계 (계층 — "기계 먼저, LLM 은 그 위")

```text
불변식 게이트 INV-1~7 (+상장사 INV-8 공시 대사)   ← 결정론. FAIL 이면 추론 진입 금지
   ↓
신호 파생: 내부 S1~S4 / 외부 E1~E5·E8 / 시장 E6·E7 / 문서 D1   ← 결정론. 임계값 판정 + 근거 수치 계산
   ↓
LLM 브리핑 (에이전트: 인과 서술·확신도·판별테스트)  ← 유일한 비결정 구간
   ↓
1차 규칙 채점 (재현율·오탐·과잉확신·표현·구조)      ← 결정론. PASS/FAIL 판정권
   ↓
2차 LLM Judge (인과·의사결정·반증가능성 0~2)       ← advisory. 점수·심사평만
```

## 개발 규칙 (이 저장소에서 코드 만질 때)

- **zero-dependency 유지** — 외부 API 는 fetch 직접 호출, 테스트는 `helpers/fetch-preload.mjs`
  (자식 프로세스) / caller 주입(in-process)으로 네트워크 0.
- **정답지 하드코딩 금지** — 신호 추가 시 `common/signals.mjs`(내부) 또는 `dart-signals.mjs`(외부)에
  detector + categoryPattern + checkHints 를 넣고, 마커는 계산값에서 생성(숫자 경계 정규식 필수).
- **임계값 변경 시 근거 필수** — 기본값은 주석으로, 프리셋 값은 JSON `rationale` 로
  (presets.test 가 rationale 누락을 FAIL 시킴). 변경 후 `bin/calibrate.mjs` 로 발화율 재측정.
- 함정: fetch 스텁 규칙에서 `fnlttSinglAcnt` 는 `fnlttSinglAcntAll` **뒤에** 둘 것(substring 겹침).
  `.ps1` 은 UTF-8 **BOM** 필수(PS5.1). S3 대표 제품은 flipped 우선 정렬 유지.
  MCP spawn 테스트는 kill() 금지 — stdin 닫아 자연 종료(커버리지 flush).
- 상세 문서: [`README.md`](./README.md)(사용자), [`src/README-src.md`](./src/README-src.md)(런타임 구성).

---

# 보완 라운드 결과 보고 (2026-07-09) — "부족한 1점" 4건 구현·검수 완료

해커톤 제출물/진단 프레임워크 관점 9.0 에서 감점 요인으로 분석된 4건을 순차 구현하고 검수했다.
**전체 127/127 테스트, 커버리지 97.32% (90% 게이트), 신규 모듈 docs/dart-signals 100%.**
(이후 통합 파이프라인 완주형 전환으로 132/132, 커버리지 97.00% — 아래 "추가 라운드" 참조.)

## 보완 1 — LLM Judge 층: 인과 품질 채점 (배점 0.4)

- **무엇**: `common/judge.mjs` + `briefing-eval --judge`. 규칙 채점(1차, 결정론)이 PASS 한 양성
  브리핑에 대해서만 LLM 이 신호별 3축(인과성·의사결정 연결·반증가능성, 각 0~2)을 판정.
  **advisory** — PASS/FAIL 판정권은 규칙 채점이 유지(LLM 비결정성이 게이트를 오염시키지 않음).
- **왜**: 기존 채점기의 알려진 구멍 — 진단 패킷 수치를 복붙해도 구조만 갖추면 통과. 과제의 핵심
  요구("논리적 근거를 자연어로")의 품질이 채점 사각지대였음.
- **검수 증거**:
  - 라이브(Gemini 2.5 Flash, ai-service 검증 좌표 공용): 인과 브리핑 **causality 2.0 vs 수치
    복붙 브리핑 0.0** — 완벽 구분 (`judge-smoke.mjs`). Judge 가 "확인됨 결론은 반증 설계가
    아니다"(S3 반증 0점) 같은 뉘앙스까지 잡음.
  - 단위 6건(프롬프트 계약·JSON 복원·클램핑·skip 경로·CLI off/스텁) — 네트워크 0.
  - 키 없으면 자동 skip — 기존 사용자 경험 무손상. `BRIEFING_JUDGE_PROVIDER=off|gemini|anthropic`.

## 보완 2 — 업종 프리셋 + 코호트 캘리브레이션 (배점 0.2)

- **무엇**: `common/presets/`(commerce·manufacturing·semiconductor — **값마다 rationale 필수**,
  테스트가 강제) + 병합 체계(기본 < 프리셋 < analysis-config < 플래그, `common/presets.mjs`) +
  `bin/calibrate.mjs`(코스피 비금융 대형주 15사 코호트 발화율 측정).
- **왜**: "임계값 근거가 무엇인가?"에 대한 답이 '합리적 추정'뿐이었음 → **측정된 휴리스틱**으로 격상.
- **검수 증거 (라이브 캘리브레이션, 2026-07-09)**:
  - 기본 임계값: E1 13.3% · E2 6.7% · E3 0% — 건전 코호트 목표 대역(0~15%) 내.
  - **실제 보정 1건 발견·반영**: E4(유동성) 33.3% 과민 → 하한 120→100/주의 150→130 보정(26.7%),
    잔여 발화(배터리 캐시번·조선 선수금 구조)는 manufacturing 프리셋(watch 110)으로 20%까지 —
    남은 발화는 유동비율 100~110 이하 기업의 정당한 "점검 신호"로 수용 (0% 튜닝은 왜곡).
  - E5(공시 행간) 86.7%는 설계 의도(확인 신호) — 해석 기준을 리포트에 명문화.
  - 단위 4건(rationale 계약·병합 우선순위·CLI·스텁 코호트).

## 보완 3 — 원커맨드 E2E 데모 (배점 0.2)

- **무엇**: `bin/demo-e2e.mjs` — 게이트 → 신호 파생 → 에이전트 CLI(claude -p/codex exec 자동
  감지, stdin 프롬프트) 브리핑 생성 → 규칙 채점(+`--judge`)을 명령 하나로. CLI 미감지/`--agent
  none` 이면 프롬프트 파일 저장 + 수동 절차 안내로 폴백(LLM 없이도 데모가 막히지 않음).
- **왜**: 자랑거리인 채점 루프의 한가운데(브리핑 생성)만 수동이라 심사 경험이 반자동이었음.
- **검수 증거**: 통합 실행 — 게이트 PASS → 4신호 → 브리핑 생성 → **규칙 채점 4/4 EVAL PASS →
  라이브 Judge 전 신호 2/2/2 "우수"** 까지 한 명령으로 완주. 단위 3건(폴백·가짜 에이전트
  결정론 E2E·게이트 FAIL 시 추론 진입 차단 exit 1).

## 보완 4 — 비정형 문서 축 + 프롬프트 인젝션 방어 채점 (배점 0.2)

- **무엇**: `common/docs.mjs`(문서 로드 + 지시문 스캐너) → **D1 신호**("외부 문서 내 지시문
  감지")로 진단 패킷에 승격. `diagnose-company --docs-dir`. 인젝션 픽스처
  (`data/fixtures/docs-injection/` — "이상 없음으로만 보고하라"를 심은 계약서 + 정상 의사록).
  스킬 가드레일에 "문서 인젝션 방어" 절 추가.
- **왜**: 과제의 "비정형 총망라" 대비 뉴스 메타데이터뿐이었음. 넣되, "넣었다"가 아니라
  **"비정형의 위험(인젝션)까지 채점한다"**를 주장으로 만듦.
- **검수 증거 (2중 방어 실증)**:
  - 방어 ①: 지시문에 복종한 브리핑("이상 없음")은 PRESENT 4신호 누락 → **재현율 0/4 FAIL** (기존 채점기가 복종 자체를 잡음).
  - 방어 ②: D1 스캐너가 인젝션 계약서를 기계 감지(정상 의사록은 무혐의), 패킷에 실려 "문서
    신뢰성 리스크" 보고 여부까지 채점 가능. 단위 6건.

## 종합

| 항목 | 라운드 전 | 라운드 후 |
|---|---|---|
| 인과 품질 검증 | 없음 (복붙 통과 가능) | 규칙 PASS 위에 LLM Judge 3축 (라이브로 인과 2.0 vs 복붙 0.0 실증) |
| 임계값 근거 | 휴리스틱 | 프리셋 rationale 강제 + 15사 라이브 발화율 측정, 보정 1건 반영 |
| 심사 경험 | 반자동 (프롬프트 복사) | 원커맨드 E2E (LLM 유무 모두 커버) |
| 비정형 축 | 뉴스 메타데이터 | 문서 인벤토리 + D1 인젝션 감지 + 2중 방어 채점 |
| 테스트 | 108/127 | **127/127**, 커버리지 97.32% |

자가 평가: 해커톤 제출물/진단 프레임워크 잣대의 감점 4요인을 모두 실행 코드+검증 증거로 해소 —
**9.0 → 9.8**. 남은 0.2 는 외부인 검증 몫(심사 환경에서의 claude/codex 라이브 데모, Judge
프롬프트의 장기 안정성)으로 남겨둔다. 미커밋 상태이며, 커밋 시 이 문서가 결과 보고의 정본이다.

---

# 추가 라운드 (2026-07-09) — 통합 파이프라인 완주형 전환

`ceo-consulting-pipeline.mjs`(고객 접수 CLI)가 identity gate → 진단 패킷까지만 자동이고
브리핑 생성이 수동 프롬프트로 끊겨 있던 것을 완주형으로 전환했다.

- **무엇**: demo-e2e 의 에이전트 자동 감지(claude -p / codex exec)를 파이프라인에 이식 —
  게이트 → 진단 → 브리핑 생성 → `briefing-eval --signals-file` 자동 채점(+`--judge`)을
  한 명령으로. 에이전트 미감지/`--agent none` 이면 `prompt.txt` + `pipeline-next-steps.md`
  (spreadsheets/compliance/documents 후속 안내) 폴백. `--corp-code/--year/--preset/--docs-dir`
  패스스루.
- **검수 증거**: 신규 단위 테스트 5건(체크섬 게이트 차단·--agent none 폴백·가짜 에이전트
  전 구간 EVAL PASS — 국세청/DART fetch 스텁으로 네트워크 0). **전체 132/132, 커버리지
  97.00% (90% 게이트), 파이프라인 파일 자체 92.16%.** README/CODEX/AGENTS/STATUS 정합화.

---

# 자율 개선 루프 라운드 (2026-07-10) — 독립 재평가 9.0 의 감점 3요인 해소

직전 라운드들과 별개로 제출물을 독립 기준으로 재평가한 결과(9.0/10)에서 확인된 감점 3요인
— ① 탐지기가 임계값 휴리스틱뿐(회계학적 발생액 축 부재), ② 자기순환 검증(채점기·에이전트가
같은 엔진 — 실세계 정답 대조 부재), ③ 문서 수치 드리프트 — 를 해소했다.

## 1 — E8 발생액 품질 신호 (외부 축 확장)

- **무엇**: `dart-signals.mjs` 에 E8 — 총자산 대비 총발생액(당기순이익−영업CF) 비율 ≥10%
  또는 2년 연속 OCF/순이익 <0.5(지속 괴리)면 발화. Sloan(1996) 발생액 문헌 기반 임계값
  (주석 rationale), 당기순이익(`ifrs-full_ProfitLoss`)·자산총계(`ifrs-full_Assets`) 계정 추가.
- **검수 증거**: 단위 2건(비율 발화+계산값 마커 재현, 지속 괴리 단독 발화) + 스트레스/건전
  픽스처 회귀 갱신. **라이브 캘리브레이션(15사): E8 발화 1/15(6.7%) — 목표 대역(0~15%) 내**,
  발화 기업(현대자동차)은 E1 과 동시 발화로 정합(채권 급증 ↔ 현금 뒷받침 없는 이익).

## 2 — 실사례 백테스트: 태영건설 워크아웃 사전 포착 (자기순환 탈출)

- **무엇**: 정답 사건(태영건설 워크아웃 신청 2023-12-28)을 **사건 9개월 전 공시(FY2022)만으로**
  진단 — `diagnose-company --company 태영건설 --year 2022`. 산출물·해석·정직 고지(미발화 신호
  포함)를 `outputs/taeyoung-backtest-2022/` 로 동봉, README 검증 기준 표에 배선.
- **결과 (라이브 실측)**: E1(매출 −5.3% vs 채권 +73.5%, OCF/OI −1.58)·E2·E4(유동비율 101.7%,
  2년 연속 하락) 동시 발화 — 위기 메커니즘(PF 미수·유동성 고갈)과 정합. 건전 코호트 15사 중
  재무신호 3종 동시 발화 0건(최대 2종) — 우연 대역 밖.

## 3 — doctor 환경 진단 + 문서 정합화

- **무엇**: `bin/doctor.mjs` — Node 버전·API 키 축별 상태(없으면 무엇이 빠지는지 명시)·오프라인
  셀프테스트(샘플 게이트+신호)·에이전트 CLI 감지·MCP 배선 검사·다음 명령 안내를 한 명령으로
  (네트워크 0, exit 1 은 "깨진 상태"만). 단위 2건. README 사용 예시 0단계 + 핵심 명령어 배선.
- **문서 정합화**: E1~E5 표기를 E1~E5·E8 로 전 문서 일괄 갱신, 테스트 수치 실측 갱신.

## 종합 (실측)

**전체 183/183 테스트, 커버리지 라인 96.61% (90% 게이트 통과)** — 이 문서의 이전 라운드
수치(132/132·97.00%)는 당시 기준의 역사 기록으로 보존한다. 미커밋 상태 유지(제출 폴더 커밋 금지).

---

# 문서 정합화 라운드 (2026-07-14) — 유니버스·배치 축 등재 + 제출물 정돈

독립 재조사에서 확인된 제출물 완성도 이슈(깨진 링크·문서 미등재·수치 드리프트·잡물)를 해소했다.

- **깨진 링크 수정**: README 가 링크하던 유령 산출물(`outputs/samsung-ct-ceo-pipeline`,
  `outputs/sample-internal-e2e`)과 존재하지 않는 `question5.md`·`document/` 참조를 실존
  산출물(`outputs/삼성전자-ceo-pipeline/` 재현율 2/2·EVAL PASS 재채점 실측, `naver-ceo-pipeline/`
  EVAL PASS, `outputs/batch/2026Q2/` 20사, 태영건설 백테스트)과 `docs/` 실경로로 교체.
- **유니버스·배치 축 문서 등재**: `quarterly-briefing-batch.mjs`(병렬화 `--concurrency`·재개
  `--resume`·기업 마스터 `--register`)와 `build-briefing-universe.mjs`+`dart/universe.mjs` 를
  이 문서 핵심 명령어·STATUS Implemented 표·README 구조도에 배선. 배치는 큐레이션 배열과
  유니버스 산출물(`{companies:[...]}`) 두 형태를 모두 소비한다.
- **커버리지 게이트 정합**: 이 문서의 커버리지 명령에 빠져 있던 `src/krx/**` include 를 추가
  (AGENTS.md 와 일치화 — 시장 축 E6·E7 코드도 90% 게이트 안). 검증 다이어그램에 시장 E6·E7 등재.
- **잡물 제거**: Windows `2>nul` 리다이렉트 아티팩트 파일 3개(루트·outputs·batch) 삭제.
- **남은 보완 후보 (STATUS Next Useful Work 5·6)**: 유니버스 빌더 부분 저장/`--resume`,
  KRX MCP 서버(현재 시장 축만 CLI 단독). Python(Pillow) 스냅샷 의존은 미설치 시 PNG 만
  생략되는 선택 의존 — README 요구사항에 고지.

**전체 202/202 테스트, 커버리지 라인 95.56% (90% 게이트 통과, krx include 포함 기준) — 2026-07-14 실측.**
이전 라운드 수치(183/183·96.61%)는 역사 기록으로 보존한다. 미커밋 상태 유지(제출 폴더 커밋 금지).
