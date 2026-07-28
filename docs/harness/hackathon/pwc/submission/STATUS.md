# STATUS.md

## Current Status

`trusted-ceo-agent`는 CEO 경영 리스크 브리핑용 Codex/Claude 호환 플러그인으로 구성되어 있습니다. 현재 구조는 내부 CSV 분석, DART 공시, ECOS 거시지표, 네이버 뉴스, 국세청 사업자등록정보 확인을 결합하는 형태입니다.

## Implemented

| 영역 | 상태 | 주요 파일 |
|---|---|---|
| 내부 CSV 불변식 게이트 | 구현됨 | `src/bin/verify-books.mjs`, `src/common/books.mjs` |
| 내부 리스크 신호 파생 | 구현됨 | `src/bin/detect-signals.mjs`, `src/common/signals.mjs` |
| API-only 기업 진단 | 구현됨 | `src/bin/diagnose-company.mjs`, `src/common/dart-signals.mjs` |
| DART MCP | 구현됨 | `src/mcp/dart-server.mjs`, `src/dart/client.mjs` |
| DART 공시 기반 CSV 생성 | 구현됨 | `src/bin/dart-to-csv.mjs` |
| ECOS MCP | 구현됨 | `src/mcp/ecos-server.mjs`, `src/ecos/client.mjs` |
| 네이버 뉴스 MCP | 구현됨 | `src/mcp/news-server.mjs`, `src/naver/client.mjs` |
| 사업자등록정보 MCP | 구현됨 | `src/mcp/registry-server.mjs`, `src/registry/client.mjs` |
| 기업 식별 게이트 | 구현됨 | `src/skills/company-identity/SKILL.md`, `company_identity_gate` |
| CEO 리스크 오케스트레이션 | 구현됨 | `src/skills/ceo-risk-recon/SKILL.md` |
| CEO 브리핑 포맷 | 구현됨 | `src/skills/ceo-briefing/SKILL.md` |
| 브리핑 자동 채점 | 구현됨 | `src/test/briefing-eval.mjs` |
| 통합 파이프라인 CLI (게이트→진단→브리핑→채점 완주) | 구현됨 + 단위 테스트 5건 | `src/bin/ceo-consulting-pipeline.mjs`, `src/test/unit/ceo-consulting-pipeline.test.mjs` |
| 엔게이지먼트 사이클 (브리핑 이후 반복 컨설팅 — 이행 추적→재진단 델타→회고 환류) | 구현됨 + 단위 테스트 5건 | `src/bin/engagement-cycle.mjs`, `src/skills/ceo-engagement-cycle/` + engagement-followup/review/retro 서브 스킬 |
| E8 발생액 품질 신호 (이익-현금 괴리, Sloan 발생액 기반) | 구현됨 + 단위 2건 + 라이브 캘리브레이션(발화율 6.7%) | `src/common/dart-signals.mjs`, `src/test/unit/dart-signals.test.mjs` |
| doctor 환경 진단 (설치 확인 원커맨드 — Node·키·셀프테스트·MCP 배선, 네트워크 0) | 구현됨 + 단위 2건 | `src/bin/doctor.mjs`, `src/test/unit/doctor.test.mjs` |
| 실사례 백테스트 (태영건설 워크아웃 2023-12 를 FY2022 공시만으로 사전 포착) | 라이브 검증 완료 (재현 명령 동봉) | `outputs/taeyoung-backtest-2022/` |
| 분기 브리핑 배치 (기업 목록 일괄 파이프라인 완주 → EVAL PASS 만 문서함 업로드, `--resume`/`--concurrency`/`--register`/`--escalate-signals` 2단 토큰 절약) | 구현됨 + 단위 테스트, 2026Q2 20사 실배치(`outputs/batch/2026Q2/`) + 무작위 50사 실측(PASS 74%) | `src/bin/quarterly-briefing-batch.mjs`, `src/test/unit/quarterly-briefing-batch.test.mjs` |
| 브리핑 유니버스 빌더 (코스피·코스닥 상장사 전체 → DART 보강 배치 목록 생성) | 구현됨 + 단위 테스트 (기본 산출물 `briefing-companies-universe.json` 은 DART 키로 실행 시 생성) | `src/bin/build-briefing-universe.mjs`, `src/dart/universe.mjs`, `src/test/unit/universe.test.mjs` |
| 최종 Word 보고서 흐름 | 문서화됨 | `README.md`, `CODEX.md`, `AGENTS.md` |

## Recommended Runtime Flow

```text
spreadsheets
  -> trusted-ceo-agent
  -> compliance-review / compliance-language
  -> documents
```

실행 가능한 파이프라인:

```powershell
node src/bin/ceo-consulting-pipeline.mjs `
  --company "<기업명>" `
  --business-number "<사업자등록번호>" `
  --data-dir "<데이터폴더>" `
  --out-dir "<산출물폴더>"
```

생성 산출물:

| 파일 | 상태 |
|---|---|
| `identity.json` | 생성 가능 |
| `diagnostic-packet.json` | 생성 가능 |
| `pipeline-next-steps.md` | 생성 가능 |
| `briefing.md` | 에이전트 CLI(claude/codex) 감지 시 자동 생성 + `briefing-eval` 자동 채점 (미감지 시 `prompt.txt` 폴백) |
| `briefing.docx` | 내장 렌더러(`render-briefing-docx`)가 자동 생성 — 표지·핵심 리스크 요약표·확신도 배지·면책 푸터, UTF-8/한글 폰트 보장 |

## Data Inputs

기본 내부 데이터 위치:

```text
src/data/sample/
|-- trial_balance.csv
|-- ar_aging.csv
`-- cost_allocation.csv
```

외부 API 키:

| 키 | 용도 |
|---|---|
| `DATA_GO_KR_API_KEY` | 국세청/data.go.kr 사업자등록정보 |
| `DART_API_KEY` | DART 기업개황, 공시, 재무제표 |
| `ECOS_API_KEY` | 기준금리, 국고채3년, USD/KRW, CPI |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 뉴스 검색 |

## Verification Status

README 기준 검증 항목:

| 항목 | 검증 방법 |
|---|---|
| 내부 장부 정합성 | `node src/bin/verify-books.mjs --data-dir <폴더>` |
| 신호 파생 | `node src/bin/detect-signals.mjs --data-dir <폴더>` |
| API-only 진단 | `node src/bin/diagnose-company.mjs --company <기업명>` |
| 브리핑 채점 | `node src/test/briefing-eval.mjs --signals-file <packet.json> briefing.md` |
| DART 연결 | `node src/test/dart-smoke.mjs` |
| ECOS 연결 | `node src/test/ecos-smoke.mjs` |
| 네이버 뉴스 연결 | `node src/test/news-smoke.mjs` |
| 사업자등록정보 연결 | `node src/test/registry-smoke.mjs` |

최근 문서/파이프라인 변경 후 확인된 사항:

- `ceo-consulting-pipeline.mjs` 완주형 전환 — 에이전트 자동 감지 브리핑 생성 + `--signals-file` 자동 채점 + `--judge` 옵션
- 분기 브리핑 배치·유니버스 빌더 추가 (`quarterly-briefing-batch.mjs` 병렬화·재개, `build-briefing-universe.mjs`)
- 전체 202/202 테스트, 커버리지 라인 95.56% (90% 게이트 통과, `src/krx/**` 포함) — 2026-07-14 실측
- `CODEX.md`/`AGENTS.md`에 파이프라인 구조 반영
- `README.md`에 `documents` / `briefing.docx` 산출 흐름 반영

## Known Boundaries

- `trial_balance_public.csv`는 DART 공시 기반 요약 재무 데이터이며 내부 장부 `trial_balance.csv`를 완전히 대체하지 않습니다.
- 거래처별 채권 aging과 제품별 원가 배분 왜곡은 내부 CSV가 있어야 분석할 수 있습니다.
- 뉴스는 정성 보조 신호입니다. 뉴스만으로 리스크를 확정하지 않습니다.
- 이 프로젝트는 CEO 경영 판단 보조용이며 투자자문/투자권유 도구가 아닙니다.

## Next Useful Work

1. ~~`ceo-consulting-pipeline.mjs`가 `briefing.md` 초안까지 자동 생성하도록 확장~~ (완료 — 에이전트 자동 감지 + 자동 채점)
2. ~~`documents` 플러그인을 통한 실제 `briefing.docx` 생성 예시 추가~~ (대체 완료 — 내장 zero-dependency 렌더러 `src/common/docx.mjs` + 예시 6종 재생성, 실제 Word COM 열림 검증)
3. `README.md`의 장기 운영 규칙을 `AGENTS.md`로 더 이동해 README를 제출용 소개 문서로 축약
4. ~~`outputs/` 예시 산출물 폴더와 샘플 `pipeline-next-steps.md` 추가~~ (완료 — `outputs/삼성전자-ceo-pipeline/`·`naver-ceo-pipeline/` 등 6벌 + `outputs/batch/2026Q2/` 20사 동봉)
5. `build-briefing-universe.mjs` 에 부분 저장/`--resume` 추가 (현재는 완주 후 1회 저장 — 중간 실패 시 전량 소실)
6. 시장 축(KRX) MCP 서버 추가 (현재 dart/ecos/news/registry 4축만 CLI+MCP 이중 노출, KRX 는 CLI 단독)
