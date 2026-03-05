# 설치 및 트러블슈팅

## 🚀 시작하기

### 1. 사전 요구사항
- Java 21
- Docker & Docker Compose
- Gradle
- **PostgreSQL 17** (로컬 설치)
- **Elastic Cloud 계정** (무료 트라이얼 가능)

### 2. PostgreSQL 로컬 설치 및 설정

#### macOS (Homebrew)
```bash
brew install postgresql@17
brew services start postgresql@17

# 데이터베이스 생성
createdb opslab
```

#### Windows
```bash
# PostgreSQL 공식 사이트에서 설치: https://www.postgresql.org/download/windows/
# 또는 Chocolatey 사용
choco install postgresql17

# 데이터베이스 생성
psql -U postgres -c "CREATE DATABASE opslab;"
```

#### Linux (Ubuntu/Debian)
```bash
sudo apt update
sudo apt install postgresql-17

# 데이터베이스 생성
sudo -u postgres createdb opslab
```

#### 사용자 및 권한 설정
```sql
-- PostgreSQL에 접속
psql -U postgres

-- 사용자 생성 및 권한 부여
CREATE DATABASE opslab;
CREATE USER inter WITH PASSWORD '1234';
GRANT ALL PRIVILEGES ON DATABASE opslab TO inter;

-- PostgreSQL 15+ 추가 권한
\c opslab
GRANT ALL ON SCHEMA public TO inter;
```

### 3. Elasticsearch Cloud 설정

#### 3-1. Elastic Cloud 계정 생성
1. [Elastic Cloud](https://cloud.elastic.co/) 방문
2. 무료 트라이얼 등록 (14일 무료)
3. **Deployment 생성**:
   - Region: 가장 가까운 리전 선택 (예: Tokyo)
   - Version: 8.x 최신 버전
   - Cloud Provider: AWS, GCP, Azure 중 선택

#### 3-2. Nori 플러그인 활성화
```bash
# Elastic Cloud Console에서:
# Deployments > [Your Deployment] > Manage > Extensions
# "analysis-nori" 플러그인 활성화
```

또는 Kibana Dev Tools에서 확인:
```
GET _cat/plugins
```

#### 3-3. 연결 정보 확인
```
Cloud ID: my-deployment:abcdef1234...
Elasticsearch Endpoint: https://my-deployment.es.us-east-1.aws.found.io:9243
Username: elastic
Password: [생성 시 제공된 비밀번호]
```

#### 3-4. application.yml 설정
```yaml
spring:
  elasticsearch:
    uris: https://my-deployment.es.us-east-1.aws.found.io:9243
    username: elastic
    password: your-password-here
```

**보안을 위해 환경 변수 사용 권장**:
```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS}
    username: ${ELASTICSEARCH_USERNAME}
    password: ${ELASTICSEARCH_PASSWORD}
```

```bash
# .env 파일 생성
export ELASTICSEARCH_URIS=https://your-deployment.es.region.cloud.es.io:9243
export ELASTICSEARCH_USERNAME=elastic
export ELASTICSEARCH_PASSWORD=your-password
```

### 4. Docker 인프라 실행 (Prometheus, Grafana)
```bash
docker-compose up -d
```

실행되는 서비스:
- **Prometheus**: `localhost:9090` (메트릭 수집)
- **Grafana**: `localhost:3000` (대시보드, admin/admin)

### 5. 애플리케이션 실행
```bash
# 환경 변수 설정 (선택사항)
export ELASTICSEARCH_URIS=https://your-deployment.es.region.cloud.es.io:9243
export ELASTICSEARCH_USERNAME=elastic
export ELASTICSEARCH_PASSWORD=your-password

# 애플리케이션 실행
./gradlew bootRun
```

애플리케이션이 시작되면:
- **API 서버**: `http://localhost:8080`
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Actuator**: `http://localhost:8080/actuator`
- **Prometheus 메트릭**: `http://localhost:8080/actuator/prometheus`

### 6. Prometheus 설정 확인 (Docker)
Prometheus가 Spring Boot 애플리케이션에서 메트릭을 수집하는지 확인:

```bash
# Prometheus 웹 UI 접속
open http://localhost:9090

# Status > Targets 메뉴에서 'spring-boot' 타겟 상태 확인
# State: UP (초록색)이면 정상
```

### 7. Grafana 대시보드 설정 (Docker)
```bash
# Grafana 웹 UI 접속 (admin/admin)
open http://localhost:3000

# 1. Data Source 추가
#    - Configuration > Data Sources > Add data source
#    - Prometheus 선택
#    - URL: http://prometheus:9090
#    - Save & Test

# 2. 대시보드 Import
#    - Dashboards > Import
#    - Import via grafana.com: 4701 (JVM Micrometer)
#    - 또는 11378 (Spring Boot Statistics)
```

### 8. Elasticsearch Cloud 인덱스 생성 확인
애플리케이션 시작 시 자동으로 `settlement_search` 인덱스가 Elastic Cloud에 생성됩니다.

**Kibana Dev Tools에서 확인**:
```
# 인덱스 확인
GET _cat/indices?v

# settlement_search 인덱스 매핑 확인
GET settlement_search/_mapping
```

**또는 curl 사용** (Basic Auth):
```bash
# 인덱스 확인
curl -u elastic:your-password \
  https://your-deployment.es.region.cloud.es.io:9243/_cat/indices?v

# settlement_search 인덱스 매핑 확인
curl -u elastic:your-password \
  https://your-deployment.es.region.cloud.es.io:9243/settlement_search/_mapping?pretty
```

## 🧪 테스트

```bash
./gradlew test
```

### 통합 테스트 시나리오

1. **부분환불 2회 누적**: refundedAmount 10000, status REFUNDED
2. **초과환불 시도**: RefundExceedsPaymentException (409)
3. **멱등성 키 재사용**: 동일 Refund 레코드 반환
4. **CONFIRMED 정산 후 환불**: SettlementAdjustment 생성
5. **PENDING 정산 후 환불**: Settlement 금액 직접 차감
6. **잘못된 상태 환불**: InvalidPaymentStateException (409)

## 🐛 트러블슈팅

### SpringDoc OpenAPI ClassNotFoundException 오류
```bash
# build.gradle.kts에 kotlin-reflect 추가됨
implementation("org.jetbrains.kotlin:kotlin-reflect")
```

### Idempotency-Key 누락
```bash
# 환불 API 호출 시 반드시 헤더 포함
curl -X POST http://localhost:8080/refunds/1 \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount": 5000.00, "reason": "고객 요청"}'
```

### Elasticsearch Cloud 연결 실패
```
ElasticsearchStatusException: method [HEAD], host [https://...], URI [/]
```

**해결 방법**:
1. **연결 정보 확인**:
   - Elastic Cloud Console에서 Endpoint URL 복사
   - Username: `elastic`
   - Password: 배포 생성 시 제공된 비밀번호

2. **application.yml 설정 확인**:
   ```yaml
   spring:
     elasticsearch:
       uris: https://your-deployment.es.region.cloud.es.io:9243
       username: elastic
       password: your-password
   ```

3. **방화벽 확인**:
   - Elastic Cloud는 기본적으로 모든 IP 허용
   - 필요시 Security > Traffic Filters에서 IP 화이트리스트 설정

### Elasticsearch Nori 플러그인 에러
```
ElasticsearchException: Unknown tokenizer type [nori_tokenizer]
```

**해결 방법 (Elastic Cloud)**:
```bash
# Elastic Cloud Console에서:
# Deployments > [Your Deployment] > Manage > Extensions
# "analysis-nori" 플러그인 활성화 후 deployment 재시작
```

**또는 Docker 환경에서**:
```bash
# Elasticsearch 컨테이너에 접속
docker exec -it lemuel-elasticsearch-1 bash
bin/elasticsearch-plugin install analysis-nori
exit

# Elasticsearch 재시작
docker-compose restart elasticsearch

# 설치 확인
curl http://localhost:9200/_cat/plugins
```

### Prometheus 타겟이 DOWN 상태
Prometheus에서 Spring Boot 타겟이 `DOWN` 상태인 경우:

1. **Spring Boot 애플리케이션이 실행 중인지 확인**:
   ```bash
   curl http://localhost:8080/actuator/prometheus
   ```

2. **Prometheus 설정 확인** (`prometheus/prometheus.yml`):
   ```yaml
   scrape_configs:
     - job_name: 'spring-boot'
       metrics_path: '/actuator/prometheus'
       static_configs:
         - targets: ['host.docker.internal:8080']
   ```

3. **Docker 네트워크 확인**:
   - macOS/Windows: `host.docker.internal` 사용
   - Linux: `172.17.0.1` 또는 호스트 IP 사용

### Grafana에서 데이터가 보이지 않음
1. **Data Source 연결 확인**:
   - Configuration > Data Sources > Prometheus
   - URL: `http://prometheus:9090` (Docker 네트워크 내부 주소)
   - Test 버튼 클릭하여 연결 확인

2. **쿼리 테스트**:
   - Explore 메뉴에서 간단한 쿼리 실행
   - 예: `http_server_requests_seconds_count`

### Elasticsearch 인덱스가 생성되지 않음

**Elastic Cloud 환경**:
```bash
# Kibana Dev Tools에서 인덱스 확인
GET _cat/indices?v

# settlement_search 인덱스가 없으면 수동 생성
# Kibana Dev Tools에서 settlement-index-settings.json 내용 붙여넣기
PUT settlement_search
{
  "settings": { ... },
  "mappings": { ... }
}
```

**또는 curl 사용**:
```bash
# 인덱스 확인
curl -u elastic:your-password \
  https://your-deployment.es.region.cloud.es.io:9243/_cat/indices?v

# settlement_search 인덱스 수동 생성
curl -u elastic:your-password \
  -X PUT https://your-deployment.es.region.cloud.es.io:9243/settlement_search \
  -H "Content-Type: application/json" \
  -d @src/main/resources/elasticsearch/settlement-index-settings.json
```

**Docker 로컬 환경**:
```bash
# 인덱스 확인
curl http://localhost:9200/_cat/indices?v

# settlement_search 인덱스 수동 생성
curl -X PUT http://localhost:9200/settlement_search \
  -H "Content-Type: application/json" \
  -d @src/main/resources/elasticsearch/settlement-index-settings.json
```

### PostgreSQL 로컬 연결 실패
```
org.postgresql.util.PSQLException: Connection refused
```

**해결 방법**:
1. **PostgreSQL 서비스 확인**:
   ```bash
   # macOS
   brew services list

   # Windows
   Get-Service postgresql-x64-17

   # Linux
   sudo systemctl status postgresql
   ```

2. **포트 확인** (기본 5432):
   ```bash
   netstat -an | grep 5432
   ```

3. **pg_hba.conf 설정**:
   ```bash
   # 로컬 연결 허용 확인
   # /etc/postgresql/17/main/pg_hba.conf (Linux)
   # /usr/local/var/postgres/pg_hba.conf (macOS)

   local   all   all   trust
   host    all   all   127.0.0.1/32   md5
   ```

## 📋 마이그레이션 가이드

1. `V4__refunds_and_settlement_adjustments.sql` 자동 실행 (Flyway)
2. 기존 음수 Payment 레코드가 있다면 수동 마이그레이션 필요
3. 환불 API 호출 시 **`Idempotency-Key` 헤더 필수**
