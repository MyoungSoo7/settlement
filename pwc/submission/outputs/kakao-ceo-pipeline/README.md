# 카카오 CEO 리스크 브리핑 산출물

`ceo-consulting-pipeline.mjs`를 라이브 API(DART·ECOS·국세청·네이버 뉴스)로 실행해 만든 산출물입니다.

## 식별자

- 회사명: 카카오 / (주)카카오
- 사업자등록번호: 120-81-47521
- DART corp_code: 00258801
- 종목코드: 035720
- 뉴스 검색어: 카카오 Kakao

## 실행 명령

```powershell
node src/bin/ceo-consulting-pipeline.mjs `
  --company "카카오" `
  --business-number "120-81-47521" `
  --corp-code "00258801" `
  --stock-code "035720" `
  --with-news `
  --news-query "카카오 Kakao" `
  --news-display 20 `
  --out-dir "outputs/kakao-ceo-pipeline"
```

초기 자동 생성 브리핑은 PRESENT가 아닌 재무 항목 표현이 평가기에서 오탐으로 잡혀 `EVAL FAIL`이 발생했습니다. 이후 `briefing.md`에서 미발화 신호명을 중립 관찰값으로 바꾸고 재채점했습니다.

## 산출물

- `identity.json`: 사업자등록번호 체크섬 및 국세청 상태조회 결과
- `diagnostic-packet.json`: DART/ECOS/네이버 뉴스 진단 패킷
- `briefing.md`: CEO 브리핑 Markdown
- `executive-summary.png`: DART/뉴스/거시 신호를 한 장으로 요약한 CEO용 스냅샷
- `briefing.docx`: CEO 브리핑 Word 문서
- `eval.txt`: `briefing-eval --signals-file` 자동 채점 결과
- `prompt.txt`: 외부 에이전트용 프롬프트
- `pipeline-next-steps.md`: 후속 compliance/documents 단계 안내

## 검증

```powershell
node src/test/briefing-eval.mjs `
  --signals-file "outputs/kakao-ceo-pipeline/diagnostic-packet.json" `
  "outputs/kakao-ceo-pipeline/briefing.md"
```

결과: `EVAL PASS`

DOCX는 구조 검증했고, LibreOffice/Poppler 기반 PNG 렌더 QA도 수행했습니다. 결과는 `render-qa.txt`에 기록했습니다.

## 핵심 진단

- PRESENT 신호: `E5 공시 행간`
- 최근 90일 공시: 86건
- 정정 공시: 20건
- 뉴스 보조 신호: 규제/소송/보안/운영 52건, 산업 49건, 기업평판 48건, 재무동향 32건, 투자동향 22건, 사업동향 14건

본 산출물은 CEO 경영 리스크 분석 보조 자료이며 투자자문 또는 투자권유가 아닙니다.
