# Settlement 사용자 매뉴얼

## 목차

1. [설치 방법](#설치-방법)
2. [실행 방법](#실행-방법)
3. [사용 가이드](#사용-가이드)
4. [주요 설정](#주요-설정)
5. [FAQ](#faq)

---

## 설치 방법

### 사전 요구사항

| 항목 | 버전 | 비고 |
|------|------|------|
| Java JDK | 21 | OpenJDK 또는 Oracle JDK |
| Docker & Docker Compose | 최신 | PostgreSQL, Elasticsearch 컨테이너 실행용 |
| Git | 최신 | |

### 프로젝트 클론

```bash
git clone <repository-url>
cd settlement
```

### 의존성 설치

```bash
./gradlew build
```

### Docker 서비스 준비

프로젝트에 포함된 `docker-compose.yml`로 다음 서비스를 구성합니다:

- **PostgreSQL 17**: 메인 데이터베이스
- **Elasticsearch 8**: 검색 엔진

---

## 실행 방법

### 1단계: Docker 서비스 시작

```bash
docker compose up -d
```

서비스 상태 확인:

```bash
docker compose ps
```

### 2단계: 백엔드 실행

```bash
./gradlew bootRun
```

| 포트 | 용도 |
|------|------|
| 8088 | 메인 API 서버 |
| 8089 | 관리(Management) 포트 |

### 실행 확인

- API 서버: `http://localhost:8088`
- 관리 포트: `http://localhost:8089`
- Swagger UI: `http://localhost:8088/swagger-ui.html`

---

## 사용 가이드

### API 문서

Swagger UI를 통해 모든 API를 확인하고 테스트할 수 있습니다:

```
http://localhost:8088/swagger-ui.html
```

### 시드 계정

V17 마이그레이션을 통해 초기 계정이 자동 생성됩니다. 데이터베이스 마이그레이션이 정상적으로 완료되었는지 확인하세요.

### 주요 기능

1. **정산 관리**: 거래 내역 기반 자동 정산 처리
2. **결제 연동**: Toss Payments 연동을 통한 결제 처리
3. **검색**: Elasticsearch 기반 고속 검색
4. **알림**: 이메일 및 Slack 알림 발송

---

## 주요 설정

### .env

```env
# 데이터베이스
DB_HOST=localhost
DB_PORT=5432
DB_NAME=settlement
DB_USERNAME=settlement_user
DB_PASSWORD=settlement_password

# JWT
JWT_SECRET=your-jwt-secret-key
JWT_EXPIRATION=3600000

# Toss Payments
TOSS_CLIENT_KEY=test_ck_...
TOSS_SECRET_KEY=test_sk_...

# 메일
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-password

# Slack
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
```

### Kubernetes 배포 시

- **ConfigMap**: 환경별 설정 (DB 호스트, 포트, 서비스 URL 등)
- **Secret**: 민감 정보 (DB 비밀번호, JWT 시크릿, API 키 등)

---

## FAQ

### Q: Toss Payments 테스트 키는 어떻게 발급받나요?

**A:** 다음 절차를 따릅니다:

1. [Toss Payments 개발자 센터](https://developers.tosspayments.com/)에 가입
2. 테스트 상점 생성
3. **개발 정보** 메뉴에서 클라이언트 키(`test_ck_...`)와 시크릿 키(`test_sk_...`)를 확인
4. `.env` 파일에 해당 키를 설정

> 테스트 키는 실제 결제가 이루어지지 않으므로 안전하게 테스트할 수 있습니다.

### Q: Elasticsearch 연결에 실패합니다.

**A:** 다음을 확인하세요:

1. Elasticsearch 컨테이너가 실행 중인지 확인:
   ```bash
   docker compose ps elasticsearch
   docker compose logs elasticsearch
   ```
2. Elasticsearch는 기동에 시간이 소요될 수 있습니다 (30초~1분). 컨테이너 로그에서 `started` 메시지를 확인한 후 애플리케이션을 시작하세요.
3. 메모리 부족으로 실패할 수 있습니다. Docker에 최소 4GB 이상의 메모리를 할당했는지 확인하세요.

### Q: 정산 배치를 수동으로 실행하려면 어떻게 하나요?

**A:** 관리 포트(8089)를 통해 수동 실행이 가능합니다. Swagger UI 또는 API 클라이언트에서 정산 배치 관련 엔드포인트를 호출하세요. 자세한 API 스펙은 Swagger UI에서 확인할 수 있습니다.
