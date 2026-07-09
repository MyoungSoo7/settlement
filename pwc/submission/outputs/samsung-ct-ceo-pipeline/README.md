# 예시 산출물 — 삼성물산(주) CEO 리스크 브리핑 (실데이터)

`ceo-consulting-pipeline.mjs` 를 라이브 API(DART·ECOS·국세청)로 실행해 만든 실제 산출물입니다.
동봉 샘플 CSV가 아니라 **공개 시장의 실기업**을 기본 모드(API-only)로 진단한 결과입니다.

| 파일 | 생성 주체 | 의미 |
|---|---|---|
| `identity.json` | 파이프라인 1단계 | 사업자등록번호 202-81-45975 체크섬 + 국세청 상태조회(계속사업자) |
| `diagnostic-packet.json` | 파이프라인 2단계 | DART 3개년 재무 + 최근 90일 공시 30건에서 파생한 외부 신호 E1~E5 (PRESENT 1건 — E5 공시 행간) |
| `briefing.md` | 에이전트 (3단계) | 서명용 CEO 브리핑 — PRESENT 1건만 리스크로 다루고 absent 4건은 승격하지 않음 |
| `briefing.docx` | `documents` 플러그인 | CEO/파트너 제출용 Word 변환본 |

## 직접 재검증

브리핑이 진단 패킷(정답지)을 통과하는지 지금 바로 채점할 수 있습니다 (네트워크 불필요).

```powershell
node src/test/briefing-eval.mjs --signals-file outputs/samsung-ct-ceo-pipeline/diagnostic-packet.json outputs/samsung-ct-ceo-pipeline/briefing.md
# 기대 결과: [재현율] 1/1 · 오탐 0 · EVAL PASS
```

같은 산출물을 처음부터 다시 만들려면 (API 키 필요):

```powershell
node src/bin/ceo-consulting-pipeline.mjs --company 삼성물산 --business-number 202-81-45975
```

주의: DART 공시는 계속 쌓이므로 재실행 시점에 따라 신호 판정(E5 등)이 달라질 수 있습니다 —
정답지를 하드코딩하지 않고 데이터에서 매번 파생하는 설계의 자연스러운 결과입니다.
