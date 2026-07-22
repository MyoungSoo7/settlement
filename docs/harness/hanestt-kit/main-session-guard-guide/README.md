# 메인세션 보호가이드 — Pass by Value vs Pass by Reference

## 한 줄

sub-agent의 ROI는 결과를 어디에 두느냐로 결정된다.

- **Pass by Value**: sub-agent를 검색 함수로 쓴다 → 메인이 종합/판단을 한다.
- **Pass by Reference**: sub-agent를 저자로 쓴다 → 메인은 인덱서/오케스트레이터가 된다.

## 이 폴더의 자료

- `pass-by-value.prompt.md` — A안 프롬프트. sub-agent가 텍스트를 반환하고, 메인이 그대로 풀어서 보여준다.
- `pass-by-reference.prompt.md` — B안 프롬프트. sub-agent가 파일로 산출물을 쓰고, 메인은 경로만 가져온다.
- `비교표.md` — 두 패턴을 4축으로 비교한 표 + "왜 깊이가 달라지는가" 의사코드.
- `실습-가이드.md` — 두 안을 같은 리포지토리에 동일 깊이로 돌려 메인 컨텍스트 차이를 본인 눈으로 보는 방법.

## 어디서 쓰는가

Chapter 1-3 [분업형 하네스 기초] / Clip 2001-3-1 [메인 세션은 왜 쉽게 지치는가] 과 Clip 2001-3-2 [sub agent와 mcp를 disposable memory로 쓰는 법]의 핵심 시연이다.

curriculum.md 기준 핵심 메시지: **메인 세션은 결정만 하고 탐색은 밖으로 보낸다.**

## 핵심 원리 — 왜 깊이가 달라지는가

```text
Pass by Value:  agent.investigate() → return summary_string   // 메인 RAM 부족이 깊이를 제약한다
Pass by Ref:    agent.investigate() → write(file); return path // 깊이 ≠ 메인 컨텍스트
```

Value 패턴은 sub의 산출 깊이가 메인 컨텍스트 한도에 묶인다. Reference 패턴은 sub의 산출이 디스크에 떨어지므로 메인 한도와 분리된다.

## 4축 비교 요약

| 축 | Pass by Value | Pass by Reference |
|---|---|---|
| 에이전트 자기검열 | "메인이 읽을 수 있는 분량으로 정리" | "파일 하나에 다 담아" |
| 메인 컨텍스트 | 요약 5개로 부풀어 오름 (~200k 잠식) | 경로 3줄 (~수백 토큰) |
| 재활용 가능성 | 세션 종료 시 휘발 | 디스크에 영속 → 다음 세션도 읽음 |
| Disposable 효과 | 부분적 (요약은 메인에 남음) | 완전 (sub 컨텍스트 전부 폐기 가능) |

## 사용 순서

1. `비교표.md`를 읽고 두 패턴의 의사코드와 4축 차이를 머릿속에 정렬한다.
2. `pass-by-value.prompt.md`를 본인 리포지토리에서 한 번 돌린다. 메인 답변 길이와 컨텍스트 사용량을 기록한다.
3. **같은 리포지토리, 같은 깊이로** `pass-by-reference.prompt.md`를 돌린다. 같은 두 수치를 기록한다.
4. `실습-가이드.md`의 가드레일을 어기지 않았는지 점검한다. 가드레일이 깨지면 두 실험의 차이가 흐려진다.
5. Chapter 1-3 실습 `2001-3-3-session-role-split.md`(역할 분리표)로 연결한다.

## 완료 기준

- 같은 리포지토리에 두 안을 각각 한 번씩 돌렸다.
- 메인 컨텍스트 사용량, 답변 길이, 다음 세션 재사용 여부 세 항목이 표로 기록되어 있다.
- "본인 업무에서 어떤 산출물이 영속화 가치가 있는가"가 한 줄로 적혀 있다.
