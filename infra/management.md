# Infra 서비스 관리 가이드

## 실행

```bash
cd infra
docker compose up -d
```

## 전체 중지

```bash
docker compose down        # 컨테이너만 제거
docker compose down -v     # 볼륨까지 제거 (데이터 삭제)
```

---

## 서비스 목록 & 접속 정보

| 서비스 | 포트 | URL | 기본 계정 |
|--------|------|-----|-----------|
| MinIO API | 9000 | - | minioadmin / minioadmin |
| MinIO Console | 9001 | http://localhost:9001 | minioadmin / minioadmin |
| Qdrant HTTP | 6333 | http://localhost:6333/dashboard | - |
| Qdrant gRPC | 6334 | - | - |
| Redis | 6379 | - | - |
| Grafana | 3001 | http://localhost:3001 | admin / admin |
| RedisInsight | 5540 | http://localhost:5540 | - |
| Portainer | 9443 | https://localhost:9443 | 최초 접속 시 설정 |
| cAdvisor | 8080 | http://localhost:8080 | - |

---

## 서비스별 사용법

### MinIO (오브젝트 스토리지)

S3 호환 오브젝트 스토리지. 파일 업로드/다운로드, 이미지 저장 등에 사용.

**콘솔 접속**: http://localhost:9001

**버킷 생성 (CLI)**:
```bash
# mc 클라이언트 설치 후
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/my-bucket
mc ls local/
```

**Spring Boot 연동** (`application.yml`):
```yaml
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: my-bucket
```

---

### Qdrant (벡터 데이터베이스)

임베딩 벡터 저장/검색. RAG, 유사도 검색 등에 사용.

**대시보드**: http://localhost:6333/dashboard

**컬렉션 생성**:
```bash
curl -X PUT http://localhost:6333/collections/my_collection \
  -H "Content-Type: application/json" \
  -d '{
    "vectors": {
      "size": 1536,
      "distance": "Cosine"
    }
  }'
```

**벡터 삽입**:
```bash
curl -X PUT http://localhost:6333/collections/my_collection/points \
  -H "Content-Type: application/json" \
  -d '{
    "points": [
      {"id": 1, "vector": [0.1, 0.2, ...], "payload": {"text": "hello"}}
    ]
  }'
```

**유사도 검색**:
```bash
curl -X POST http://localhost:6333/collections/my_collection/points/search \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.1, 0.2, ...],
    "limit": 5
  }'
```

---

### Redis (캐시 / 세션)

캐시, 세션 스토어, 메시지 큐 등에 사용. AOF 영속화 활성화됨.

**CLI 접속**:
```bash
docker exec -it infra-redis redis-cli
```

**기본 명령어**:
```bash
SET key "value"
GET key
KEYS *
DEL key
TTL key
FLUSHALL           # 전체 삭제 (주의)
INFO memory        # 메모리 사용량
```

**Spring Boot 연동** (`application.yml`):
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

---

### Grafana (대시보드)

Prometheus, Loki 등 데이터소스를 시각화하는 대시보드.

**접속**: http://localhost:3001 (admin / admin, 최초 로그인 시 비밀번호 변경)

**Prometheus 데이터소스 연결**:
1. 좌측 메뉴 > Connections > Data sources > Add data source
2. Prometheus 선택
3. URL: `http://host.docker.internal:9090` (메인 docker-compose의 Prometheus)
4. Save & Test

**cAdvisor 메트릭 대시보드 추가**:
1. 좌측 메뉴 > Dashboards > Import
2. Dashboard ID: `14282` 입력 (cAdvisor 공식 대시보드)
3. Prometheus 데이터소스 선택 > Import

**유용한 대시보드 ID**:
- `14282` - cAdvisor (Docker 컨테이너 모니터링)
- `11835` - Redis Dashboard
- `763`  - PostgreSQL Overview
- `7589` - Elasticsearch Overview

---

### RedisInsight (Redis GUI)

Redis 데이터를 GUI로 조회/수정할 수 있는 관리 도구.

**접속**: http://localhost:5540

**Redis 연결 추가**:
1. 접속 후 "Add Redis database" 클릭
2. Host: `infra-redis`, Port: `6379` 입력
3. Add Redis Database 클릭

**주요 기능**:
- Browser: 키 조회/편집/삭제
- Workbench: Redis 명령어 실행
- Analysis: 메모리 분석, 키 분포 확인
- Slow Log: 느린 명령어 추적

---

### Portainer (Docker 관리 GUI)

Docker 컨테이너, 이미지, 볼륨, 네트워크를 웹에서 관리.

**접속**: https://localhost:9443

**최초 설정**:
1. 접속 시 관리자 계정 생성 (비밀번호 12자 이상)
2. "Get Started" > local 환경 선택
3. 대시보드에서 전체 컨테이너 상태 확인 가능

**주요 기능**:
- Containers: 시작/중지/재시작, 로그 보기, 콘솔 접속
- Images: 이미지 관리, 사용하지 않는 이미지 정리
- Volumes: 볼륨 확인/삭제
- Networks: 네트워크 구성 확인

---

### cAdvisor (컨테이너 메트릭)

컨테이너별 CPU, 메모리, 네트워크, 디스크 I/O 실시간 모니터링.

**접속**: http://localhost:8080

**주요 페이지**:
- `/containers/` - 전체 컨테이너 목록
- `/docker/` - Docker 컨테이너별 상세 메트릭

**Prometheus 연동**:
메인 `monitoring/prometheus.yml`에 scrape target 추가:
```yaml
scrape_configs:
  - job_name: 'cadvisor'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

---

## 기존 앱에서 infra 서비스 접근하기

기존 docker-compose의 앱에서 infra 서비스에 접근하려면, 메인 `docker-compose.yml`에 external 네트워크를 추가:

```yaml
# docker-compose.yml (메인)
services:
  app:
    networks:
      - default
      - infra-net
    environment:
      SPRING_DATA_REDIS_HOST: infra-redis
      MINIO_ENDPOINT: http://infra-minio:9000

networks:
  infra-net:
    external: true
```

> infra를 먼저 `docker compose up -d` 한 뒤 메인을 올려야 네트워크가 존재합니다.

---

## 문제 해결

**포트 충돌 시**:
```bash
# 어떤 프로세스가 포트를 사용 중인지 확인
netstat -ano | findstr :9000
```

**볼륨 초기화** (데이터 완전 삭제):
```bash
docker compose down -v
```

**특정 서비스만 재시작**:
```bash
docker compose restart redis
docker compose logs -f redis     # 로그 확인
```

**전체 상태 확인**:
```bash
docker compose ps
```
