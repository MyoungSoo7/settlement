# 스파르타 MSA 과정 개별 프로젝트


## 📅 제출 방법

### 1️⃣ Branch 생성 및 작업

제공된 주차별 Git repository에서 **신규 Branch**를 생성한 뒤 작업합니다.

> **Branch 이름 형식**
> ```
> work/{팀번호}-{영문 이름}
> ```
> 예: `work/1-john-doe`


### 2️⃣ Commit 및 Push

작업 내용을 **작업용 브랜치**에 Commit하고 Push합니다.


### 3️⃣ PR 요청

작업이 완료되면 **작업용 브랜치**에서 **제출용 브랜치**로 PR(Pull Request)을 생성합니다.

> **제출용 브랜치 이름 형식**
> ```
> project/{팀번호}-{영문 이름}
> ```
> 예: `project/1-john-doe`


### 4️⃣ PR 리뷰 및 병합

리뷰가 완료되면 **제출용 브랜치**에 PR을 병합합니다.


### 🔄 전체 흐름 요약

```
신규 Branch 생성 (work/...)
        ↓
     작업 진행
        ↓
  Commit & Push
        ↓
PR 생성 (work/... → project/...)
        ↓
    리뷰 완료
        ↓
  PR 병합 ✅
```
