# 2004-3-3 workspace — review gate 입력 번들

`2004-3-3-stop-and-requestion-criteria.md` 실습을 바로 시작할 수 있게, 입력으로 필요한 자료를 미리 깔아 둔 폴더다. **산출물(gate 자체와 dry-run verdict)은 여기 없다 — 그건 실습에서 본인이 만든다.** 이 폴더는 입력만 담는다.

## 파일

| 파일 | 무엇 | 실습에서 쓰는 곳 |
|---|---|---|
| `handoff-L3-result.md` | 4-2 build 세션이 돌려준 가상 결과 한 장 (L3 · 6칸 맵핑 + 근거 줄) | gate의 검토 대상 |
| `meeting.md` | 근거 대조용 회의록 원문 (chapter-03 sample-meeting.md 복사본) | evidence stage가 줄번호로 직접 대조 |
| `inputs.md` | 실습 `## 입력` 7칸을 채운 표 + 그대로 붙일 프롬프트 | AI에게 시키기 |

## 의존 체인

```text
2004-1-3-workspace/ac-tree-meeting-to-1page.md   (L1~L6 작업 트리, L3를 4-2로 선택)
        │
2004-2-3-workspace/handoff-L3-build-to-main.md   (handoff 양식 v1 + L3 build 예시)
        │
2004-3-3-workspace/  ← 여기                       (그 L3 handoff를 review gate에 통과)
```

## 2-3 산출물과 다른 점 한 가지

2004-2-3-workspace의 handoff 예시(§2 Result)에는 `근거(출처)` 줄이 빠져 있다. 2-3 실습 문서(line 36·106)는 근거 줄을 요구하는데 산출물에서 누락된 상태라, 그대로 두면 3-3 evidence stage가 대조할 줄이 없다. 그래서 `handoff-L3-result.md`에는 `meeting.md` 줄번호를 가리키는 근거(출처) 줄을 채워 두었다.

## 시작

1. `inputs.md`의 입력표 + 프롬프트를 복사한다.
2. 새 세션에 붙여 deterministic review gate를 만든다.
3. `handoff-L3-result.md`를 검토 대상으로 넣고 pass / rework / ask-back / exit 중 하나로 라우팅한다.
4. 같은 handoff를 한 번 더 넣어 같은 verdict가 나오는지 본다.
