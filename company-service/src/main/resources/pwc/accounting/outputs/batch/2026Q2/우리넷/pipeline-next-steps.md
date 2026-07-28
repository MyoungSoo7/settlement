# CEO Consulting Pipeline Next Steps

## Pipeline

```text
spreadsheets
  -> trusted-ceo-agent (identity gate -> diagnose -> briefing -> eval)
  -> compliance-review / compliance-language
  -> documents
```

## Confirmed Inputs

- 기업명: `우리넷`
- 사업자등록번호: `124-81-68611`
- 내부 표준 CSV 폴더: 미제공. DART/ECOS/뉴스 기반 외부 진단 모드로 진행.
- 식별 게이트 결과: `C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\identity.json`
- 진단 패킷: `C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\diagnostic-packet.json`

## 1. Spreadsheets

고객 원본 Excel/CSV가 있으면 먼저 `spreadsheets` 플러그인으로 다음 표준 파일을 만든다.

```text
trial_balance.csv
ar_aging.csv
cost_allocation.csv
```

이미 `--data-dir` 로 표준 CSV 폴더를 넘겼다면 이 단계는 완료된 것으로 본다.

## 2. Trusted CEO Agent — 브리핑

이미 생성·채점됨: `C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\briefing.md` — 아래 프롬프트는 재생성용.

```text
기업명 우리넷, 사업자등록번호 124-81-68611 기준으로 identity.json과 diagnostic-packet.json을 근거로 CEO 브리핑을 작성해줘.
내부 CSV가 있으면 정합성 게이트와 신호 파생 결과를 우선하고, DART/ECOS/네이버 뉴스 신호를 보조 근거로 결합해줘.
각 리스크는 결론, 근거 수치, 확신도, 추가 확인 절차, 권고 조치 순서로 작성해줘.
검토용 산출물은 C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\briefing.md 로 저장해줘.
```

## 3. Compliance Review / Language

브리핑 확정 전 아래를 검수한다.

- PII: 대표자명, 계좌번호, 주민번호, 카드번호, 실명+연락처 조합이 불필요하게 노출되지 않는가
- Auditability: 어떤 수치가 어떤 파일/API/시점에서 왔는지 추적 가능한가
- Language: 수익·원금을 약속하는 보장형 표현, 주가 방향을 단정하는 표현, 매매를 지시하는 표현이 없는가
- Boundary: CEO 경영 판단 보조 자료이며 투자자문/투자권유가 아님을 명시했는가

## 4. Documents

Word 보고서는 파이프라인이 내장 렌더러로 자동 생성한다 (표지·핵심 리스크 요약표·확신도
배지·면책 푸터 포함, UTF-8/한글 폰트 보장):

```text
C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\briefing.docx   ← 이미 생성됨 (브리핑 수정 후 재생성: node src/bin/render-briefing-docx.mjs C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\briefing.md)
```

회사 브랜드 템플릿(로고·표지 규정)이 필요한 경우에만 `documents` 플러그인으로 변환한다.

## Verification

```powershell
node src/test/briefing-eval.mjs --signals-file "C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\diagnostic-packet.json" "C:\Users\iamip\IdeaProjects\kubenetis\settlement\pwc\submission\outputs\batch\2026Q2\우리넷\briefing.md"
```
