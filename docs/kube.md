# Kubernetes + ArgoCD GitOps 배포 가이드

## k8s 파일별 역할 요약

Kubernetes는 선언형(declarative) 시스템이다. YAML 파일에 "원하는 상태"를 선언하면
클러스터가 그 상태를 유지해준다.

```
k8s/
├── namespace.yaml          # 프로젝트 전용 격리 공간 생성
├── configmap.yaml          # 비밀 아닌 설정값 (DB 호스트, 포트 등)
├── sealed-secret.yaml      # 암호화된 민감 정보 (비밀번호, JWT 등)
├── deployment.yaml         # Spring Boot 백엔드 Pod 실행 및 관리
├── frontend-deployment.yaml # React/Nginx 프론트엔드 Pod + Service
├── service.yaml            # 백엔드를 외부/Ingress에서 접근 가능하게 노출
├── ingress.yaml            # URL 경로별 라우팅 규칙 (Nginx Ingress Controller)
├── postgresql-pv.yaml      # PostgreSQL 스토리지 + StatefulSet + Service
├── elasticsearch-pv.yaml   # Elasticsearch 스토리지 + StatefulSet + Service
├── batch-cronjob.yaml      # 정산 배치 스케줄 (매일 01:00, 02:00)
└── argocd-app.yaml         # ArgoCD GitOps 자동 배포 등록
```

### 핵심 개념 한눈에 보기

| 리소스 | 역할 | 비유 |
|--------|------|------|
| **Namespace** | 프로젝트 격리 공간 | 폴더 |
| **ConfigMap** | 비밀 아닌 환경변수 묶음 | .env 파일 |
| **SealedSecret** | 암호화된 환경변수 묶음 | 암호화된 .env 파일 |
| **Deployment** | 앱 Pod를 N개 유지, 무중단 업데이트 | 앱 실행 설정서 |
| **StatefulSet** | DB처럼 상태 있는 Pod 실행 (이름 고정) | DB 실행 설정서 |
| **Service** | Pod 여러 개를 하나의 주소로 묶음 | 내부 로드밸런서 |
| **Ingress** | 도메인/경로 기반 HTTP 라우팅 | Nginx 리버스 프록시 |
| **PV / PVC** | 디스크 공간 정의 / 사용 신청 | 하드디스크 / 마운트 |
| **CronJob** | 주기적 Job 실행 (crontab) | 배치 스케줄러 |

### 트래픽 흐름

```
사용자 브라우저
    │
    ▼
<서버IP>:80 (NGINX Ingress Controller)
    │
    ├── /api/*, /auth/*, /orders/* 등  ──→  lemuel-service:8080  ──→  Spring Boot Pod
    │
    └── /*  ──────────────────────────────→  lemuel-frontend-service:80  ──→  Nginx Pod
                                                                                │
                                                                    React 정적 파일 응답
```

### 리소스 의존 관계 (적용 순서)

```
namespace.yaml
    └── sealed-secret.yaml (lemuel-secret 생성)
    └── configmap.yaml (lemuel-config 생성)
        └── postgresql-pv.yaml (PV → PVC → StatefulSet → Service)
        └── elasticsearch-pv.yaml (PV → PVC → StatefulSet → Service)
            └── deployment.yaml (lemuel-secret + lemuel-config 참조)
            └── frontend-deployment.yaml
                └── service.yaml
                └── ingress.yaml (lemuel-service + lemuel-frontend-service 참조)
                └── batch-cronjob.yaml (lemuel-secret + lemuel-config 참조)

argocd-app.yaml  ← ArgoCD 설치 후 별도로 한 번만 적용
```

---

## 아키텍처 개요

```
GitHub (main 브랜치) → ArgoCD → Ubuntu K8s 클러스터
                                  ├── lemuel-app (Spring Boot)
                                  ├── lemuel-frontend (React/Nginx)
                                  ├── postgresql (StatefulSet)
                                  ├── elasticsearch (StatefulSet)
                                  └── settlement-batch (CronJob x2)
```

---

## 수정 내역 (GitOps 전환 시 발견된 문제)

### 1. `deployment.yaml` — maxUnavailable 수정

**문제**: `replicas: 1` 환경에서 `maxUnavailable: 1`이면 업데이트 중 구 Pod 먼저 종료 → 신규 Pod 기동까지 **다운타임 발생**.

**수정**: `maxUnavailable: 0`으로 변경. `maxSurge: 1`과 함께 신규 Pod 먼저 Running 상태가 된 후 구 Pod를 종료해 무중단 배포 보장.

```yaml
rollingUpdate:
  maxSurge: 1
  maxUnavailable: 0  # 변경
```

---

### 2. `elasticsearch-pv.yaml` — xpack.security 비활성화 시 ELASTIC_PASSWORD 제거

**문제**: `xpack.security.enabled: "false"`로 설정했으나 `ELASTIC_PASSWORD` 환경변수도 함께 설정 → 보안 비활성 상태에서 password 설정은 무시되므로 혼동 유발.

**수정**: `ELASTIC_PASSWORD` 환경변수를 주석 처리. 운영 환경에서 `xpack.security.enabled: "true"`로 변경 시 주석 해제.

---

### 3. `frontend-deployment.yaml` — Probe 경로 수정 + imagePullSecrets 추가

**문제 1**: liveness/readiness/startup probe 경로가 `/health` → 기본 nginx 이미지에는 해당 경로 없어 probe 실패, Pod 재시작 반복.

**수정**: `/health` → `/` (nginx 기본 루트)로 변경.

**문제 2**: GHCR private registry 이미지를 pull하는데 `imagePullSecrets` 누락.

**수정**: `imagePullSecrets: [{name: ghcr-secret}]` 추가.

---

### 4. `batch-cronjob.yaml` — imagePullSecrets 추가

**문제**: 두 CronJob(`settlement-batch-creation`, `settlement-batch-confirmation`) 모두 GHCR 이미지 사용하는데 `imagePullSecrets` 누락 → ImagePullBackOff 발생.

**수정**: 두 CronJob의 `spec.jobTemplate.spec.template.spec`에 `imagePullSecrets: [{name: ghcr-secret}]` 추가.

---

### 5. `argocd-app.yaml` 신규 생성

ArgoCD가 `https://github.com/MyoungSoo7/settlement` `main` 브랜치의 `k8s/` 디렉토리를 자동 동기화하는 Application 매니페스트 추가.

주요 설정:
- `automated.prune: true` — Git에서 제거된 리소스 자동 삭제
- `automated.selfHeal: true` — 클러스터 상태 드리프트 자동 복구
- `CreateNamespace=true` — `lemuel` 네임스페이스 자동 생성
- `ServerSideApply=true` — CRD(SealedSecret 등) 대용량 리소스 적용 지원

---

## 배포 전 필수 작업 (수동)

### 0. GHCR 이미지 이름

이미지는 GitHub username 기준으로 자동 설정됨 (소문자):
- 백엔드: `ghcr.io/myoungsoo7/settlement:latest`
- 프론트엔드: `ghcr.io/myoungsoo7/settlement-frontend:latest`

> GHCR 패키지 이름은 GitHub Actions 워크플로우에서 `docker/build-push-action`의 `tags` 값과 일치해야 함.

### 1. Kubernetes 설치 (Ubuntu 단일 노드)

```bash
# kubeadm + containerd
sudo apt-get update && sudo apt-get install -y kubelet kubeadm kubectl
sudo kubeadm init --pod-network-cidr=10.244.0.0/16

# kubeconfig 설정
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config

# 단일 노드에서 Pod 스케줄링 허용
kubectl taint nodes --all node-role.kubernetes.io/control-plane-

# CNI 플러그인 (Flannel)
kubectl apply -f https://raw.githubusercontent.com/flannel-io/flannel/master/Documentation/kube-flannel.yml
```

### 2. NGINX Ingress Controller 설치

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/baremetal/deploy.yaml
```

### 3. Sealed Secrets 컨트롤러 설치

```bash
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets -n kube-system
```

### 4. GHCR 이미지 Pull Secret 생성

```bash
kubectl create namespace lemuel

kubectl create secret docker-registry ghcr-secret \
  --docker-server=ghcr.io \
  --docker-username=<GITHUB_USERNAME> \
  --docker-password=<GITHUB_PAT> \
  --docker-email=<EMAIL> \
  -n lemuel
```

> GitHub PAT는 `read:packages` 권한 필요.

### 5. Sealed Secret 값 채우기

```bash
# kubeseal CLI 설치
wget https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.26.0/kubeseal-0.26.0-linux-amd64.tar.gz
tar xzf kubeseal-*.tar.gz && sudo mv kubeseal /usr/local/bin/

# 각 값 암호화 (예: POSTGRES_USER)
echo -n "실제_유저명" | kubeseal --raw \
  --from-file=/dev/stdin \
  --namespace lemuel \
  --name lemuel-secret

# 출력된 암호화 문자열을 sealed-secret.yaml의 해당 필드에 붙여넣기
```

또는 기존 secret.yaml로 한번에 생성:

```bash
# secret.yaml에 실제 값 입력 후 (절대 커밋 금지)
kubeseal --format yaml < k8s/secret.yaml > k8s/sealed-secret.yaml
```

### 6. PV 디렉토리 사전 생성 (Ubuntu 서버)

```bash
sudo mkdir -p /data/k8s/postgresql
sudo mkdir -p /data/k8s/elasticsearch
sudo chmod 777 /data/k8s/elasticsearch  # elasticsearch는 root가 아닌 uid 1000으로 실행
```

### 7. ArgoCD 설치 및 Application 등록

```bash
# ArgoCD 설치
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# ArgoCD UI 접근 (NodePort)
kubectl patch svc argocd-server -n argocd -p '{"spec": {"type": "NodePort"}}'

# 초기 admin 비밀번호 확인
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d

# https://github.com/MyoungSoo7/settlement 는 public 레포이므로 별도 인증 설정 불필요

# Application 등록
kubectl apply -f k8s/argocd-app.yaml
```

---

## 배포 순서 (초기 1회)

```bash
# 1. 네임스페이스
kubectl apply -f k8s/namespace.yaml

# 2. Sealed Secret (암호화 값 채운 후)
kubectl apply -f k8s/sealed-secret.yaml

# 3. ConfigMap
kubectl apply -f k8s/configmap.yaml

# 4. PV/PVC
kubectl apply -f k8s/postgresql-pv.yaml
kubectl apply -f k8s/elasticsearch-pv.yaml

# 5. 데이터베이스 (기동 대기)
kubectl wait --for=condition=ready pod -l app=postgresql -n lemuel --timeout=120s
kubectl wait --for=condition=ready pod -l app=elasticsearch -n lemuel --timeout=180s

# 6. 앱
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/frontend-deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/batch-cronjob.yaml
```

이후부터는 `main` 브랜치에 push하면 **ArgoCD가 자동으로 동기화**.

---

## 주의사항

| 항목 | 내용 |
|------|------|
| `secret.yaml` | `.gitignore`에 추가됨. 절대 커밋 금지 |
| `sealed-secret.yaml` | Git 커밋 가능. 암호화된 값만 포함 |
| `argocd-app.yaml` | `repoURL`: `https://github.com/MyoungSoo7/settlement` (public, 인증 불필요) |
| 이미지 태그 | `latest` 사용 중 → 운영 시 Git SHA 태그 권장 |
| Elasticsearch 보안 | 현재 `xpack.security: false`. 운영 시 활성화 후 password 주석 해제 |
| Ingress 도메인 | `lemuel.local` → 운영 시 실제 도메인으로 변경 + TLS 활성화 |