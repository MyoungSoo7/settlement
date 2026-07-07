# KakaoPay Invest Companion — 해커톤 제출 패키지

초보 투자자가 매수·매도 시 겪는 **불안감과 정보 부족**(카카오페이증권 AI 서비스센터가 정의한
문제)을 해결하는 Codex 플러그인. 분석 결과를 던지는 대신,
**감정 인정 → 데이터 사실확인 → 규칙 상기 → 다음 단계 1개** 의 설득 구조로 사용자를
안심시켜 다음 행동으로 이끈다.

## 스킬 구성 (3 + 1)

| 스킬 | 역할 |
|---|---|
| `anxiety-triage` | 불안 발화를 4유형(손실 공포/FOMO/정보 부족/확신 부족)으로 분류하고 4단계 프로토콜로 응대 |
| `next-step-guide` | 투자 단계(S0~S5) 상태머신 — 상태별 **다음 행동 딱 1개** 제시 (결정 마비 해소) |
| `trust-explainer` | AI 판정 전달 시 "결론 → 근거(출처·시점) → **반대 근거** → 한계·고지" 4단 구조 강제 |
| `kakaopay-sample` | 제출 구조 데모용 샘플 |

데이터 근거가 필요할 때는 같은 저장소의 invest-copilot MCP 도구(`invest_signal`,
`fin_metrics`, `econ_latest`, `reputation_score`)를 사용하고, 도구가 없는 환경에서는
사용자에게 사실을 질문하는 방식으로 우아하게 강등된다.

## Structure

```text
submission.zip
|-- src/
|   |-- .codex-plugin/plugin.json
|   |-- skills/anxiety-triage/SKILL.md
|   |-- skills/next-step-guide/SKILL.md
|   |-- skills/trust-explainer/SKILL.md
|   |-- skills/kakaopay-sample/SKILL.md
|   |-- .mcp.json
|   |-- bin/run-sample.ps1
|   `-- README-src.md
|-- README.md
`-- logs/          # Stop 훅이 자동 저장하는 대화 로그 (logs/<tool>/<session>.jsonl)
```

## Run

```powershell
powershell -ExecutionPolicy Bypass -File src/bin/run-sample.ps1
```

## 컴플라이언스

모든 스킬은 보장/단정 표현("수익 보장" 등) 금지, 지시형("사라/팔아라") 대신 기준 충족형
서술, 응답 말미 필수 고지문을 강제한다. 본 패키지의 출력은 교육 목적 정보 제공이며
투자자문·투자권유가 아니다.
