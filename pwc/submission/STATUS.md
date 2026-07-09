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
| `briefing.docx` | `documents` 플러그인 단계에서 생성 |

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
- 파이프라인 단위 테스트 5건(네트워크 0, fetch 스텁) 포함 전체 132/132, 커버리지 게이트 90% 통과
- `CODEX.md`/`AGENTS.md`에 파이프라인 구조 반영
- `README.md`에 `documents` / `briefing.docx` 산출 흐름 반영

## Known Boundaries

- `trial_balance_public.csv`는 DART 공시 기반 요약 재무 데이터이며 내부 장부 `trial_balance.csv`를 완전히 대체하지 않습니다.
- 거래처별 채권 aging과 제품별 원가 배분 왜곡은 내부 CSV가 있어야 분석할 수 있습니다.
- 뉴스는 정성 보조 신호입니다. 뉴스만으로 리스크를 확정하지 않습니다.
- 이 프로젝트는 CEO 경영 판단 보조용이며 투자자문/투자권유 도구가 아닙니다.

## Next Useful Work

1. ~~`ceo-consulting-pipeline.mjs`가 `briefing.md` 초안까지 자동 생성하도록 확장~~ (완료 — 에이전트 자동 감지 + 자동 채점)
2. `documents` 플러그인을 통한 실제 `briefing.docx` 생성 예시 추가
3. `README.md`의 장기 운영 규칙을 `AGENTS.md`로 더 이동해 README를 제출용 소개 문서로 축약
4. `outputs/` 예시 산출물 폴더와 샘플 `pipeline-next-steps.md` 추가 (`outputs/samsung-ct-ceo-pipeline` 존재)
