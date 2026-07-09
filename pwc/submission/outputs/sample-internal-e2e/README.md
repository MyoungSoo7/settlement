# 예시 산출물 — 상세 모드 원커맨드 E2E (내부 CSV, 라이브 LLM)

`demo-e2e.mjs --judge` 한 명령을 라이브(claude CLI 브리핑 생성 + Gemini Judge)로 실행해
만든 실제 산출물입니다. 동봉 샘플 CSV(`src/data/sample/`)의 내부 신호 S1~S4 전부를 다루는
상세 모드 브리핑이 생성·채점까지 완주한 기록입니다.

| 파일 | 생성 주체 | 의미 |
|---|---|---|
| `packet.json` | 신호 파생 (2단계) | 불변식 게이트 PASS 후 파생된 내부 신호 S1~S4 (전부 PRESENT) |
| `prompt.txt` | 파이프라인 (3단계 입력) | 에이전트에 전달된 브리핑 생성 프롬프트 (오탐 금지 규칙 포함) |
| `briefing.md` | claude CLI (3단계) | 서명용 CEO 브리핑 — 4개 리스크 × 인과 사슬·확신도·판별 테스트 |

## 실행 당시 채점 결과 (2026-07-09)

```text
[재현율] 4/4 — S1 수익-현금 괴리 · S2 거래처 신용 집중 · S3 원가 배분 왜곡 · S4 차입 의존 성장
[정밀도/과잉 확신] 위반 없음 · [표현 안전성] 위반 없음 → EVAL PASS

LLM Judge (advisory · gemini): S1~S4 전부 인과 2/2 · 의사결정 2/2 · 반증가능성 2/2 — 종합 "우수"
```

## 직접 재검증

규칙 채점은 네트워크 없이 지금 바로 재현됩니다 (`--judge` 는 GEMINI_API_KEY 필요).

```powershell
node src/test/briefing-eval.mjs --data-dir src/data/sample outputs/sample-internal-e2e/briefing.md
# 기대 결과: [재현율] 4/4 · EVAL PASS
```

같은 산출물을 처음부터 다시 만들려면 (claude 또는 codex CLI 필요):

```powershell
node src/bin/demo-e2e.mjs --judge --out outputs/sample-internal-e2e
```

브리핑은 LLM이 생성하므로 문장은 실행마다 다르지만, 채점 기준(신호·마커)은 데이터에서
파생되므로 "4/4 EVAL PASS" 재현 여부 자체가 프레임워크의 검증력을 보여줍니다.
