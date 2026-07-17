#!/usr/bin/env bash
# =============================================================================
# run.sh — 클러스터 다이어그램 생성기 실행 스크립트
#
# 요구 환경 : Python 3.10+ (matplotlib, numpy)
# 실행 방법 : ./run.sh            → 3개 프리셋 이미지를 out/ 에 생성
#             ./run.sh <config>   → 지정한 JSON 설정만 렌더링
#
# 예)  ./run.sh
#      ./run.sh configs/kubernetes.json
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# 1) 의존성 확인 (없으면 설치)
python3 -c "import matplotlib, numpy" 2>/dev/null || {
    echo "[setup] matplotlib / numpy 설치 중..."
    python3 -m pip install --quiet matplotlib numpy
}

# 2) 렌더링
render() {
    local config="$1"
    local name
    name="$(basename "${config%.json}")"
    python3 -m src.main --config "$config" --output "out/${name}.png"
}

if [[ $# -ge 1 ]]; then
    render "$1"
else
    echo "[1/3] 팔란티어 스타일 (과제 예시 재현)"
    render configs/palantir.json
    echo "[2/3] 쿠버네티스 — 의미 입력 → 도형 매핑"
    render configs/kubernetes.json
    echo "[3/3] 회사 시스템(결제·정산 도메인)"
    render configs/company_system.json
fi

echo
echo "완료. 결과물: $(pwd)/out/"
python3 -m src.main --list-shapes
