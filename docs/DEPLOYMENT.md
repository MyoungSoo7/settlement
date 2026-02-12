# Lemuel Full-Stack Deployment Guide

## 아키텍처 개요

```
                    Internet
                        |
                   [ Ingress ]
                    /       \
                   /         \
          [ Frontend ]    [ Backend API ]
          (Nginx, 2 Pods)  (Spring Boot, 3 Pods)
                |              |
                |              +---[ PostgreSQL ]
                |              +---[ Elasticsearch ]
                |
         [ Static Assets ]
```

### 경로 라우팅

- `/` → 프론트엔드 (React SPA)
- `/api/*` → 백엔드 API
- `/auth/*` → 인증 API
- `/orders/*`, `/payments/*`, `/refunds/*` → 백엔드 API
- `/actuator/*` → Spring Boot Actuator
- `/swagger-ui/*` → Swagger UI

---

## 1. 프론트엔드 빌드 및 Docker 이미지 생성

### 로컬 빌드 테스트

```bash
cd frontend

# 의존성 설치
npm install

# 프로덕션 빌드
npm run build

# 빌드 결과 확인
ls -la dist/
```

### Docker 이미지 빌드

```bash
cd frontend

# Docker 이미지 빌드
docker build -t lemuel-frontend:latest .

# 로컬 테스트
docker run -p 8081:80 lemuel-frontend:latest

# 접속 테스트: http://localhost:8081
```

### GitHub Container Registry에 푸시

```bash
# GitHub Container Registry 로그인
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin

# 이미지 태그
docker tag lemuel-frontend:latest ghcr.io/your-org/lemuel-frontend:latest

# 푸시
docker push ghcr.io/your-org/lemuel-frontend:latest
```

---

## 2. K8s 배포

### 전체 배포 순서

```bash
# 1. Namespace 생성
kubectl apply -f k8s/namespace.yaml

# 2. ConfigMap 및 Secret 생성
kubectl apply -f k8s/configmap.yaml

# Secret은 직접 생성 (Git에 커밋하지 말 것!)
kubectl create secret generic lemuel-secret \
  --from-literal=POSTGRES_USER=myuser \
  --from-literal=POSTGRES_PASSWORD=mypassword \
  --from-literal=ELASTICSEARCH_USER=elastic \
  --from-literal=ELASTICSEARCH_PASSWORD=changeme \
  --from-literal=JWT_SECRET=your-256-bit-secret-key \
  -n lemuel

# 3. 데이터베이스 배포 (PV + StatefulSet)
kubectl apply -f k8s/postgresql-pv.yaml
kubectl apply -f k8s/elasticsearch-pv.yaml

# 데이터베이스 준비 대기 (약 1-2분)
kubectl wait --for=condition=ready pod -l app=postgresql -n lemuel --timeout=300s
kubectl wait --for=condition=ready pod -l app=elasticsearch -n lemuel --timeout=300s

# 4. 백엔드 API 배포
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# 5. 프론트엔드 배포
kubectl apply -f k8s/frontend-deployment.yaml

# 6. Ingress 배포 (경로 라우팅)
kubectl apply -f k8s/ingress.yaml

# 7. 배치 CronJob 배포
kubectl apply -f k8s/batch-cronjob.yaml
```

### 배포 확인

```bash
# 전체 Pod 상태 확인
kubectl get pods -n lemuel

# 출력 예시:
# NAME                              READY   STATUS    RESTARTS   AGE
# lemuel-app-7d8f5b9c8d-abc12       1/1     Running   0          2m
# lemuel-app-7d8f5b9c8d-def34       1/1     Running   0          2m
# lemuel-app-7d8f5b9c8d-ghi56       1/1     Running   0          2m
# lemuel-frontend-5f6c8d9e7f-jkl78  1/1     Running   0          1m
# lemuel-frontend-5f6c8d9e7f-mno90  1/1     Running   0          1m
# postgresql-0                       1/1     Running   0          5m
# elasticsearch-0                    1/1     Running   0          5m

# Service 확인
kubectl get svc -n lemuel

# Ingress 확인
kubectl get ingress -n lemuel

# 백엔드 로그 확인
kubectl logs -f -l app=lemuel -n lemuel --tail=100

# 프론트엔드 로그 확인
kubectl logs -f -l app=lemuel-frontend -n lemuel --tail=100
```

---

## 3. /etc/hosts 설정

### 우분투 서버

```bash
sudo nano /etc/hosts

# 추가
127.0.0.1 lemuel.local
```

### 로컬 PC (개발 환경)

**Windows**:
```
C:\Windows\System32\drivers\etc\hosts

<우분투서버IP> lemuel.local
```

**macOS/Linux**:
```bash
sudo nano /etc/hosts

<우분투서버IP> lemuel.local
```

---

## 4. 접속 및 테스트

### 프론트엔드 접속

```
http://lemuel.local/
```

### 백엔드 API 테스트

```bash
# Health Check
curl http://lemuel.local/actuator/health

# Swagger UI
http://lemuel.local/swagger-ui/index.html

# API 직접 호출
curl http://lemuel.local/api/settlements/search
```

### 로그인 테스트

1. `http://lemuel.local/login` 접속
2. 테스트 계정으로 로그인
3. 주문/결제 페이지 테스트
4. 정산 대시보드 확인

---

## 5. 이미지 업데이트 및 롤링 업데이트

### 백엔드 업데이트

```bash
# 1. Git push → GitHub Actions 자동 빌드 → GHCR 푸시

# 2. K8s에서 이미지 업데이트
kubectl set image deployment/lemuel-app \
  lemuel=ghcr.io/your-org/lemuel:latest \
  -n lemuel

# 또는 재배포
kubectl rollout restart deployment/lemuel-app -n lemuel

# 롤링 업데이트 상태 확인
kubectl rollout status deployment/lemuel-app -n lemuel
```

### 프론트엔드 업데이트

```bash
# 1. Git push → GitHub Actions 자동 빌드 → GHCR 푸시

# 2. K8s에서 이미지 업데이트
kubectl set image deployment/lemuel-frontend \
  frontend=ghcr.io/your-org/lemuel-frontend:latest \
  -n lemuel

# 또는 재배포
kubectl rollout restart deployment/lemuel-frontend -n lemuel

# 롤링 업데이트 상태 확인
kubectl rollout status deployment/lemuel-frontend -n lemuel
```

---

## 6. CORS 설정 확인

### Ingress에서 CORS 허용 (이미 설정됨)

`k8s/ingress.yaml`:
```yaml
annotations:
  nginx.ingress.kubernetes.io/enable-cors: "true"
  nginx.ingress.kubernetes.io/cors-allow-methods: "GET, POST, PUT, PATCH, DELETE, OPTIONS"
  nginx.ingress.kubernetes.io/cors-allow-headers: "Authorization, Content-Type, X-Requested-With, Idempotency-Key"
  nginx.ingress.kubernetes.io/cors-allow-credentials: "true"
```

### 백엔드 SecurityConfig에서 CORS 허용 (이미 설정됨)

`SecurityConfig.java`:
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(
        "http://localhost:3000",
        "http://localhost:5173",
        "http://lemuel.local"  // K8s Ingress 도메인
    ));
    // ...
}
```

**중요**: K8s 환경에서는 프론트엔드와 백엔드가 같은 도메인(`lemuel.local`)을 사용하므로 CORS 문제가 발생하지 않습니다!

---

## 7. 모니터링

### Pod 리소스 사용량

```bash
# CPU/메모리 사용량
kubectl top pods -n lemuel

# 실시간 로그 (백엔드)
kubectl logs -f -l app=lemuel -n lemuel

# 실시간 로그 (프론트엔드)
kubectl logs -f -l app=lemuel-frontend -n lemuel

# 특정 Pod 로그
kubectl logs -f lemuel-app-7d8f5b9c8d-abc12 -n lemuel
```

### Ingress 로그

```bash
# Ingress Controller 로그
kubectl logs -n ingress-nginx -l app.kubernetes.io/name=ingress-nginx
```

### 메트릭

```bash
# Prometheus 메트릭
curl http://lemuel.local/actuator/prometheus
```

---

## 8. 트러블슈팅

### 프론트엔드가 백엔드 API 호출 실패

#### 증상
- 브라우저 콘솔에 CORS 에러
- Network 탭에서 `/api/` 요청이 404 또는 500

#### 해결 방법
1. Ingress 경로 확인
   ```bash
   kubectl describe ingress lemuel-ingress -n lemuel
   ```

2. 백엔드 Service가 정상인지 확인
   ```bash
   kubectl get svc lemuel-service -n lemuel
   kubectl get endpoints lemuel-service -n lemuel
   ```

3. 백엔드 Pod가 Running 상태인지 확인
   ```bash
   kubectl get pods -l app=lemuel -n lemuel
   ```

4. 브라우저 개발자 도구에서 실제 요청 URL 확인
   - 프로덕션: `http://lemuel.local/api/settlements/search`
   - 개발: `http://localhost:8080/api/settlements/search`

### 프론트엔드 404 에러

#### 증상
- React 라우팅이 작동하지 않음
- `/dashboard` 접속 시 404 에러

#### 해결 방법
1. Nginx 설정 확인 (`frontend/nginx.conf`)
   ```nginx
   location / {
       try_files $uri $uri/ /index.html;
   }
   ```

2. 프론트엔드 Pod 재시작
   ```bash
   kubectl rollout restart deployment/lemuel-frontend -n lemuel
   ```

### Ingress가 작동하지 않음

#### 증상
- `lemuel.local` 접속 불가
- Connection refused 에러

#### 해결 방법
1. Ingress Controller 설치 확인
   ```bash
   kubectl get pods -n ingress-nginx
   ```

2. Ingress Controller 설치 (없는 경우)
   ```bash
   kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/baremetal/deploy.yaml
   ```

3. Ingress 상태 확인
   ```bash
   kubectl describe ingress lemuel-ingress -n lemuel
   ```

### 데이터베이스 연결 실패

#### 해결 방법
```bash
# PostgreSQL Pod 확인
kubectl exec -it postgresql-0 -n lemuel -- psql -U myuser -d opslab

# Elasticsearch 확인
kubectl exec -it elasticsearch-0 -n lemuel -- curl localhost:9200
```

---

## 9. 백업 및 복원

### PostgreSQL 백업

```bash
# 백업
kubectl exec postgresql-0 -n lemuel -- pg_dump -U myuser opslab > backup.sql

# 복원
kubectl exec -i postgresql-0 -n lemuel -- psql -U myuser opslab < backup.sql
```

### Elasticsearch 스냅샷

```bash
# 스냅샷 Repository 등록
curl -X PUT "lemuel.local/actuator/health" # Elasticsearch 스냅샷 API 활용
```

---

## 10. 보안 체크리스트

- [ ] `k8s/secret.yaml`을 Git에 커밋하지 않았는지 확인
- [ ] JWT Secret이 256-bit 이상인지 확인
- [ ] PostgreSQL 비밀번호가 강력한지 확인
- [ ] Actuator 엔드포인트 접근 제한 (운영 환경)
- [ ] HTTPS 설정 (Let's Encrypt + cert-manager)
- [ ] Image Pull Secret 설정 (Private Registry 사용 시)
- [ ] Network Policy 적용 (Pod 간 통신 제한)
- [ ] RBAC 권한 최소화

---

## 11. 스케일링

### 수동 스케일링

```bash
# 백엔드 Pod 증가
kubectl scale deployment lemuel-app --replicas=5 -n lemuel

# 프론트엔드 Pod 증가
kubectl scale deployment lemuel-frontend --replicas=3 -n lemuel
```

### 자동 스케일링 (HPA)

```bash
# Horizontal Pod Autoscaler 생성
kubectl autoscale deployment lemuel-app \
  --cpu-percent=70 \
  --min=3 \
  --max=10 \
  -n lemuel

# HPA 상태 확인
kubectl get hpa -n lemuel
```

---

## 참고 자료

- [INFRASTRUCTURE.md](INFRASTRUCTURE.md) - 백엔드 인프라 상세 가이드
- [frontend/SETUP.md](../frontend/SETUP.md) - 프론트엔드 개발 가이드
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [NGINX Ingress Controller](https://kubernetes.github.io/ingress-nginx/)
