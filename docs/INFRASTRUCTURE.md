# Lemuel Infrastructure Guide

## 개요

Lemuel은 정산 시스템을 위한 Spring Boot 애플리케이션으로, Kubernetes(K8s) 환경에서 High Availability(HA) 구성으로 운영됩니다.

### 아키텍처 구성

- **Application**: Spring Boot 3.5.x + Java 21
- **Framework**: Spring Batch, Spring Data JPA, Spring Security
- **Database**: PostgreSQL 16
- **Search Engine**: Elasticsearch 8.11
- **Container**: Docker + Kubernetes
- **CI/CD**: GitHub Actions → GitHub Container Registry (GHCR)

---

## 1. 애플리케이션 무상태성 및 이중화

### 1.1 Spring Boot 설정 변경 사항

#### Graceful Shutdown 설정 (`application.yml`)
```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```
- K8s Rolling Update 시 진행 중인 요청이 완료될 때까지 최대 30초 대기
- SIGTERM 신호를 받으면 새로운 요청을 거부하고 기존 요청 완료 후 종료

#### Health Check 설정
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```
- **Liveness Probe**: `/actuator/health/liveness` - Pod가 살아있는지 확인
- **Readiness Probe**: `/actuator/health/readiness` - 트래픽을 받을 준비가 되었는지 확인
- **Startup Probe**: 초기 시작 시간이 긴 애플리케이션을 위한 프로브

### 1.2 Spring Batch 중복 실행 방지

#### BatchConfig 개선 사항
```java
@Configuration
@EnableBatchProcessing
public class BatchConfig {

    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobRepository.setJobRepository(jobRepository); // DB 기반 Repository 사용
        jobLauncher.setTaskExecutor(new SyncTaskExecutor()); // 동기 실행
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }
}
```

**중복 실행 방지 메커니즘**:
1. **DB 기반 JobRepository**: PostgreSQL의 `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION` 테이블 사용
2. **Pessimistic Lock**: 동일한 Job이 여러 Pod에서 동시 실행되지 않도록 DB 레벨에서 차단
3. **CronJob 방식**: K8s CronJob으로 배치를 실행하면 한 번에 하나의 Pod만 생성됨

#### Batch Job 실행 방식
```yaml
spring:
  batch:
    job:
      enabled: false  # 애플리케이션 시작 시 자동 실행 방지
```
- API 서버 Pod는 배치를 실행하지 않음
- K8s CronJob이 별도 Pod를 생성하여 배치 실행
- `--spring.batch.job.names=createSettlementJob` 파라미터로 특정 Job 실행

---

## 2. Docker 및 CI/CD

### 2.1 Dockerfile

**멀티 스테이지 빌드**로 이미지 크기 최적화:

```dockerfile
# Stage 1: Build
FROM gradle:8.5-jdk21-alpine AS builder
WORKDIR /app
COPY ../build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon || true
COPY ../src ./src
RUN gradle bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**최적화 포인트**:
- Gradle 의존성 캐싱으로 빌드 시간 단축
- JRE만 포함하여 이미지 크기 감소 (JDK 불필요)
- Non-root 유저로 실행 (보안)

### 2.2 GitHub Actions Workflow

**`.github/workflows/deploy.yml`**:
1. 코드 체크아웃
2. JDK 21 설치 및 Gradle 캐싱
3. 테스트 실행
4. Gradle 빌드 (`bootJar`)
5. Docker 이미지 빌드 및 GHCR 푸시
6. 태그 전략:
   - `master` 브랜치 → `latest` 태그
   - 커밋 SHA → `master-abc1234` 태그
   - PR → PR 번호 태그

**이미지 저장소**:
- **GitHub Container Registry (GHCR)**: `ghcr.io/your-org/lemuel:latest`
- Docker Hub 사용 시 주석 해제하여 전환 가능

---

## 3. Kubernetes 이중화 구성

### 3.1 디렉토리 구조

```
k8s/
├── namespace.yaml              # Namespace 정의
├── configmap.yaml              # 환경 변수 (non-sensitive)
├── secret.yaml                 # 민감 정보 (DB 비밀번호 등)
├── deployment.yaml             # 애플리케이션 Deployment (replicas: 3)
├── service.yaml                # Service (NodePort)
├── ingress.yaml                # Ingress (NGINX)
├── postgresql-pv.yaml          # PostgreSQL + PersistentVolume
├── elasticsearch-pv.yaml       # Elasticsearch + PersistentVolume
└── batch-cronjob.yaml          # Spring Batch CronJob
```

### 3.2 Deployment 설정 (HA 구성)

**`k8s/deployment.yaml`**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lemuel-app
spec:
  replicas: 3  # 3개의 Pod 실행
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
```

**핵심 설정**:
- **Replicas: 3**: 동시에 3개의 Pod가 실행되어 가용성 보장
- **RollingUpdate**: 무중단 배포 (한 번에 1개씩 교체)
- **PodAntiAffinity**: 가능하면 다른 노드에 배포 (단일 노드에서는 무시됨)
- **terminationGracePeriodSeconds: 60**: Pod 종료 시 60초 대기

**리소스 제한**:
```yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "2Gi"
    cpu: "1000m"
```

**Health Probes**:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 5
```

### 3.3 Service 및 Ingress

#### Service (NodePort)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: lemuel-service
spec:
  type: NodePort
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 30080  # 외부 접근: http://<서버IP>:30080
```

#### Ingress (NGINX)
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: lemuel-ingress
spec:
  rules:
    - host: lemuel.local
      http:
        paths:
          - path: /
            backend:
              service:
                name: lemuel-service
                port:
                  number: 8080
```

**접속 방법**:
1. **NodePort 직접 접근**: `http://<우분투서버IP>:30080`
2. **Ingress 사용**: `/etc/hosts`에 `<서버IP> lemuel.local` 추가 후 `http://lemuel.local`

### 3.4 데이터 영속성 (PersistentVolume)

#### PostgreSQL
```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: postgresql-pv
spec:
  capacity:
    storage: 20Gi
  hostPath:
    path: "/data/k8s/postgresql"  # 우분투 서버 경로
    type: DirectoryOrCreate
```

#### Elasticsearch
```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: elasticsearch-pv
spec:
  capacity:
    storage: 30Gi
  hostPath:
    path: "/data/k8s/elasticsearch"
    type: DirectoryOrCreate
```

**데이터 보존**:
- `persistentVolumeReclaimPolicy: Retain`: PVC 삭제 시에도 데이터 유지
- Pod가 재시작되어도 `/data/k8s/` 디렉토리의 데이터는 그대로 유지됨

### 3.5 Spring Batch CronJob

**`k8s/batch-cronjob.yaml`**:
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: settlement-batch-creation
spec:
  schedule: "0 1 * * *"  # 매일 새벽 1시
  concurrencyPolicy: Forbid  # 중복 실행 방지
```

**배치 실행 방식**:
- API 서버 Pod와 별도로 CronJob이 배치 전용 Pod 생성
- `concurrencyPolicy: Forbid`로 이전 Job이 끝나지 않으면 새로운 Job 실행 안 함
- DB 기반 JobRepository로 이중 실행 방지

---

## 4. 배포 가이드

### 4.1 사전 준비 (우분투 서버)

#### 1. Kubernetes 설치 (단일 노드)
```bash
# K3s 설치 (경량 K8s)
curl -sfL https://get.k3s.io | sh -

# kubectl 설정
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
```

#### 2. 데이터 디렉토리 생성
```bash
sudo mkdir -p /data/k8s/postgresql
sudo mkdir -p /data/k8s/elasticsearch
sudo chmod 777 /data/k8s/postgresql
sudo chmod 777 /data/k8s/elasticsearch
```

#### 3. NGINX Ingress Controller 설치
```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/baremetal/deploy.yaml
```

### 4.2 애플리케이션 배포

#### 1. Secret 생성 (민감 정보)
```bash
kubectl create secret generic lemuel-secret \
  --from-literal=POSTGRES_USER=myuser \
  --from-literal=POSTGRES_PASSWORD=mypassword \
  --from-literal=ELASTICSEARCH_USER=elastic \
  --from-literal=ELASTICSEARCH_PASSWORD=changeme \
  --from-literal=JWT_SECRET=your-256-bit-secret-key \
  -n lemuel
```

#### 2. K8s 리소스 배포
```bash
# 순서대로 배포
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/postgresql-pv.yaml
kubectl apply -f k8s/elasticsearch-pv.yaml

# DB 준비 완료 대기 (약 1-2분)
kubectl wait --for=condition=ready pod -l app=postgresql -n lemuel --timeout=300s
kubectl wait --for=condition=ready pod -l app=elasticsearch -n lemuel --timeout=300s

# 애플리케이션 배포
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/batch-cronjob.yaml
```

#### 3. 배포 확인
```bash
# Pod 상태 확인
kubectl get pods -n lemuel

# Service 확인
kubectl get svc -n lemuel

# Ingress 확인
kubectl get ingress -n lemuel

# 로그 확인
kubectl logs -f -l app=lemuel -n lemuel
```

### 4.3 배포 업데이트

#### GitHub Actions 자동 배포
```bash
# master 브랜치에 푸시하면 자동으로:
# 1. Docker 이미지 빌드
# 2. GHCR에 푸시 (ghcr.io/your-org/lemuel:latest)
git push origin master
```

#### K8s에서 이미지 업데이트
```bash
# 이미지 Pull 및 Rolling Update
kubectl set image deployment/lemuel-app \
  lemuel=ghcr.io/your-org/lemuel:latest \
  -n lemuel

# 또는 전체 재배포
kubectl rollout restart deployment/lemuel-app -n lemuel

# Rollout 상태 확인
kubectl rollout status deployment/lemuel-app -n lemuel
```

### 4.4 롤백
```bash
# 이전 버전으로 롤백
kubectl rollout undo deployment/lemuel-app -n lemuel

# 특정 버전으로 롤백
kubectl rollout history deployment/lemuel-app -n lemuel
kubectl rollout undo deployment/lemuel-app --to-revision=2 -n lemuel
```

---

## 5. 모니터링 및 로깅

### 5.1 기본 모니터링

```bash
# Pod CPU/메모리 사용량
kubectl top pods -n lemuel

# 노드 리소스
kubectl top nodes

# 실시간 로그 확인
kubectl logs -f -l app=lemuel -n lemuel --tail=100

# 특정 Pod 로그
kubectl logs -f <pod-name> -n lemuel
```

### 5.2 Prometheus 메트릭

애플리케이션은 `/actuator/prometheus` 엔드포인트로 메트릭 노출:
- `refund.processing.duration`
- `settlement.batch.creation.duration`
- `settlement.batch.confirmation.duration`

**Prometheus + Grafana 설치 (선택사항)**:
```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
```

### 5.3 배치 Job 모니터링

```bash
# CronJob 목록
kubectl get cronjobs -n lemuel

# 최근 실행된 Job
kubectl get jobs -n lemuel

# Job 로그 확인
kubectl logs job/settlement-batch-creation-<timestamp> -n lemuel
```

---

## 6. 트러블슈팅

### 6.1 Pod가 시작되지 않을 때

```bash
# Pod 상태 확인
kubectl describe pod <pod-name> -n lemuel

# 이벤트 확인
kubectl get events -n lemuel --sort-by='.lastTimestamp'

# 컨테이너 로그 확인
kubectl logs <pod-name> -n lemuel --previous  # 이전 컨테이너 로그
```

**일반적인 원인**:
- ImagePullBackOff: 이미지를 다운로드할 수 없음 → GHCR 권한 확인
- CrashLoopBackOff: 애플리케이션 시작 실패 → 로그 확인
- Pending: 리소스 부족 → `kubectl top nodes` 확인

### 6.2 DB 연결 실패

```bash
# PostgreSQL Pod 확인
kubectl exec -it postgresql-0 -n lemuel -- psql -U myuser -d opslab

# 연결 테스트
kubectl run -it --rm debug --image=postgres:16-alpine --restart=Never -n lemuel -- \
  psql -h postgresql-service -U myuser -d opslab
```

### 6.3 배치 중복 실행 확인

```bash
# DB에서 배치 실행 이력 확인
kubectl exec -it postgresql-0 -n lemuel -- psql -U myuser -d opslab -c \
  "SELECT job_instance_id, job_name, job_key, start_time, end_time, status
   FROM opslab.batch_job_execution
   ORDER BY start_time DESC LIMIT 10;"
```

### 6.4 성능 이슈

```bash
# Thread Dump
kubectl exec <pod-name> -n lemuel -- jcmd 1 Thread.print

# Heap Dump
kubectl exec <pod-name> -n lemuel -- jcmd 1 GC.heap_dump /tmp/heap.hprof
kubectl cp <pod-name>:/tmp/heap.hprof ./heap.hprof -n lemuel
```

---

## 7. 보안 체크리스트

- [ ] **Secret 관리**: `k8s/secret.yaml`을 Git에 커밋하지 말 것
- [ ] **Image Pull Secret**: Private Registry 사용 시 설정
- [ ] **Network Policy**: Pod 간 통신 제한 (선택사항)
- [ ] **RBAC**: Service Account 권한 최소화
- [ ] **Elasticsearch Security**: 운영 환경에서는 `xpack.security.enabled: true` 활성화
- [ ] **Ingress TLS**: Let's Encrypt + cert-manager로 HTTPS 설정
- [ ] **Database Backup**: 정기적인 PostgreSQL 백업 설정

---

## 8. 다음 단계

### 단일 노드 → 멀티 노드 확장
1. Worker 노드 추가:
   ```bash
   # Master 노드에서 Join Token 확인
   sudo kubeadm token create --print-join-command

   # Worker 노드에서 실행
   sudo kubeadm join <master-ip>:6443 --token <token> --discovery-token-ca-cert-hash <hash>
   ```

2. `hostPath` → `NFS` 또는 `Ceph` 스토리지로 전환
3. Load Balancer (MetalLB) 설치

### CI/CD 개선
- ArgoCD / Flux로 GitOps 구현
- Helm Chart로 배포 템플릿화
- Canary Deployment 전략 적용

### 관찰성 (Observability)
- ELK Stack (Elasticsearch + Logstash + Kibana) 로깅
- Jaeger 분산 트레이싱
- Prometheus + Grafana 대시보드

---

## 참고 자료

- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Batch Documentation](https://docs.spring.io/spring-batch/docs/current/reference/html/)
- [Kubernetes Best Practices](https://kubernetes.io/docs/concepts/configuration/overview/)
- [K3s - Lightweight Kubernetes](https://k3s.io/)
- [NGINX Ingress Controller](https://kubernetes.github.io/ingress-nginx/)
