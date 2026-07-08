# 트러블슈팅 가이드

## Toss API 연결 실패

**증상**: 결제 확인 시 타임아웃 또는 인증 에러 발생

**원인**: Toss API 시크릿 키가 잘못되었거나 네트워크 문제

**해결 방법**:
1. `TOSS_SECRET_KEY` 환경변수 확인: 올바른 시크릿 키인지 검증
2. Toss API URL이 올바른지 확인 (테스트/운영 환경 구분)
3. 서버에서 Toss API 도메인으로의 네트워크 연결 확인
4. 방화벽 규칙에서 Toss API 아웃바운드 허용 여부 확인

---

## 정산 배치 실패

**증상**: 일일 정산이 생성되지 않음, 정산 누락

**원인**: 배치 실행 중 DB 연결 실패 또는 예외 발생

**해결 방법**:
1. 배치 로그 확인: `settlement.batch.creation.duration` 메트릭 조회
2. PostgreSQL 연결 상태 확인: `docker compose logs postgres`
3. 배치 실행 시간(02:00) 전후 서버 로그 확인
4. 수동 재실행이 필요한 경우 해당 날짜 지정하여 배치 트리거

---

## Elasticsearch 인덱싱 실패

**증상**: 정산 검색이 되지 않음, 인덱스 데이터 누락

**원인**: Elasticsearch 클러스터 상태 이상 또는 인덱싱 큐 적체

**해결 방법**:
1. ES 헬스 확인: `curl -X GET "localhost:9200/_cluster/health?pretty"`
2. 재시도 큐 확인: `settlement_index_queue` 상태 모니터링
3. 인덱스 매핑 확인: `curl -X GET "localhost:9200/settlement/_mapping?pretty"`
4. 필요 시 인덱스 재생성 후 전체 재인덱싱

---

## 환불 이중 차감

**증상**: 동일 결제 건에 대해 환불이 중복 처리됨

**원인**: 동시 환불 요청 시 경합 조건 발생

**해결 방법**:
1. `Isolation.REPEATABLE_READ`가 적용되어 있으므로 정상적으로 방지되어야 함
2. 동시성 관련 로그 확인 (트랜잭션 격리 위반 에러)
3. 중복 환불이 발생한 경우 Toss API에서 실제 환불 상태 확인
4. DB의 결제 상태와 PG사 상태 간 불일치 시 수동 보정

---

## CORS 에러

**증상**: 프론트엔드에서 API 호출 시 CORS 정책 에러

**원인**: 운영 환경에서 `CORS_ORIGINS` 환경변수가 설정되지 않음

**해결 방법**:
1. `CORS_ORIGINS` 환경변수 설정: 프론트엔드 도메인 지정
2. 여러 도메인인 경우 콤마로 구분
3. 설정 변경 후 서버 재시작
4. 프리플라이트 요청(OPTIONS)이 정상 응답하는지 확인

---

## PDF 생성 실패

**증상**: 정산서 PDF 다운로드 시 에러 또는 빈 파일 생성

**원인**: Ghostscript가 설치되지 않았거나 공유 메모리 부족

**해결 방법**:
1. Ghostscript 설치 확인: `gs --version`
2. Docker 환경인 경우 `shm_size: 256m` 설정 확인 (`docker-compose.yml`)
3. 임시 디렉토리 쓰기 권한 확인
4. PDF 템플릿 파일이 올바른 경로에 있는지 확인

---

## JaCoCo 커버리지 미달

**증상**: CI 빌드 실패, 커버리지 기준 미달 에러

**원인**: 테스트 커버리지가 설정된 최소 기준에 미달

**해결 방법**:
1. 커버리지 리포트 생성: `./gradlew test jacocoTestReport`
2. 리포트 확인: `build/reports/jacoco/test/html/index.html`
3. 커버리지가 낮은 클래스 확인 후 테스트 추가
4. 제외 대상이 올바르게 설정되었는지 `build.gradle` 확인

---

## Branch Protection 푸시 실패

**증상**: `git push`가 거부됨, main/develop 브랜치에 직접 푸시 불가

**원인**: GitHub branch protection 규칙이 적용되어 직접 푸시가 차단됨

**해결 방법**:
1. 별도 브랜치에서 작업 후 Pull Request 생성
2. PR 리뷰 및 CI 통과 후 머지
3. `git checkout -b feature/작업명` 으로 브랜치 생성 후 작업
