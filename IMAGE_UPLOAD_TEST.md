# 상품 이미지 업로드 테스트 시나리오

## 사전 준비
```bash
# 업로드 디렉토리 생성
mkdir -p /data/uploads/products

# 테스트 이미지 준비
# test.jpg, test2.png 등의 이미지 파일 준비
```

## 환경 변수 설정
```bash
# application.yml에 추가되어 있어야 함
app:
  upload:
    dir: /data/uploads
    base-url: /assets
```

## 테스트 시나리오

### 1. 이미지 업로드 (다중 파일)
```bash
# JWT 토큰 획득 (ADMIN 권한 필요)
TOKEN="your-jwt-token-here"

# 상품 ID = 1에 이미지 업로드
curl -X POST http://localhost:8080/admin/products/1/images \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "files=@test.jpg" \
  -F "files=@test2.png"

# 응답 예시:
# [
#   {
#     "id": 1,
#     "productId": 1,
#     "originalFileName": "test.jpg",
#     "url": "/assets/products/1/550e8400-e29b-41d4-a716-446655440000.jpg",
#     "contentType": "image/jpeg",
#     "sizeBytes": 102400,
#     "width": 1920,
#     "height": 1080,
#     "isPrimary": true,
#     "orderIndex": 0,
#     "createdAt": "2025-01-15T10:00:00"
#   },
#   {
#     "id": 2,
#     "productId": 1,
#     "originalFileName": "test2.png",
#     "url": "/assets/products/1/660e8400-e29b-41d4-a716-446655440001.png",
#     "contentType": "image/png",
#     "sizeBytes": 204800,
#     "width": 800,
#     "height": 600,
#     "isPrimary": false,
#     "orderIndex": 1,
#     "createdAt": "2025-01-15T10:00:01"
#   }
# ]
```

### 2. 이미지 목록 조회
```bash
# 상품의 이미지 목록 조회
curl -X GET http://localhost:8080/admin/products/1/images \
  -H "Authorization: Bearer ${TOKEN}"

# 응답: 위와 동일한 이미지 배열
```

### 3. 대표 이미지 변경
```bash
# 이미지 ID = 2를 대표 이미지로 지정
curl -X PATCH http://localhost:8080/admin/products/1/images/2/primary \
  -H "Authorization: Bearer ${TOKEN}"

# 응답:
# {
#   "id": 2,
#   "productId": 1,
#   "originalFileName": "test2.png",
#   "url": "/assets/products/1/660e8400-e29b-41d4-a716-446655440001.png",
#   "isPrimary": true,
#   "orderIndex": 1,
#   ...
# }
```

### 4. 이미지 순서 변경
```bash
# 이미지 순서 재배치 (imageId 순서대로)
curl -X PATCH http://localhost:8080/admin/products/1/images/reorder \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "imageIds": [2, 1]
  }'

# 응답: 새로운 순서가 적용된 이미지 배열
```

### 5. 이미지 삭제
```bash
# 이미지 ID = 1 삭제
curl -X DELETE http://localhost:8080/admin/products/1/images/1 \
  -H "Authorization: Bearer ${TOKEN}"

# 응답: 204 No Content
# 주의: 대표 이미지 삭제 시 남은 이미지 중 첫 번째가 자동으로 대표 이미지로 설정됨
```

## 검증 포인트

### 1. 파일 타입 검증
```bash
# PDF 파일 업로드 시도 (실패해야 함)
curl -X POST http://localhost:8080/admin/products/1/images \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "files=@test.pdf"

# 응답: 400 Bad Request
# {"message": "Invalid image type: application/pdf"}
```

### 2. 파일 크기 검증
```bash
# 10MB 이미지 업로드 시도 (실패해야 함)
curl -X POST http://localhost:8080/admin/products/1/images \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "files=@large_image.jpg"

# 응답: 400 Bad Request
# {"message": "File size exceeds 5MB limit"}
```

### 3. 권한 검증
```bash
# 토큰 없이 업로드 시도 (실패해야 함)
curl -X POST http://localhost:8080/admin/products/1/images \
  -F "files=@test.jpg"

# 응답: 401 Unauthorized
```

### 4. 이미지 서빙 확인
```bash
# 업로드된 이미지 URL로 직접 접근
curl -I http://localhost:8080/assets/products/1/550e8400-e29b-41d4-a716-446655440000.jpg

# 응답: 200 OK
# Content-Type: image/jpeg
# Cache-Control: public, immutable
```

### 5. 상품 조회 시 대표 이미지 포함 확인
```bash
# 상품 목록 조회
curl -X GET http://localhost:8080/api/products \
  -H "Authorization: Bearer ${TOKEN}"

# 응답:
# [
#   {
#     "id": 1,
#     "name": "테스트 상품",
#     "price": 10000,
#     "primaryImageUrl": "/assets/products/1/660e8400-e29b-41d4-a716-446655440001.png",
#     ...
#   }
# ]
```

## 에러 처리 테스트

### 존재하지 않는 상품에 이미지 업로드
```bash
curl -X POST http://localhost:8080/admin/products/99999/images \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "files=@test.jpg"

# 응답: 404 Not Found 또는 500 (FK constraint 위반)
```

### 존재하지 않는 이미지 삭제
```bash
curl -X DELETE http://localhost:8080/admin/products/1/images/99999 \
  -H "Authorization: Bearer ${TOKEN}"

# 응답: 404 Not Found
# {"message": "Image not found: 99999"}
```

### 다른 상품의 이미지를 대표로 지정
```bash
# 상품 ID = 1에서 상품 ID = 2의 이미지를 대표로 지정 시도
curl -X PATCH http://localhost:8080/admin/products/1/images/10/primary \
  -H "Authorization: Bearer ${TOKEN}"

# 응답: 400 Bad Request
# {"message": "Image does not belong to product"}
```

## Docker 환경에서 테스트

### Docker Compose로 실행
```bash
# Docker Compose 빌드 및 실행
docker-compose -f docker-compose-with-volumes.yml up -d

# 볼륨 확인
docker volume ls | grep upload

# 컨테이너 내부의 업로드 디렉토리 확인
docker exec -it lemuel_backend_1 ls -la /data/uploads/products

# Nginx를 통한 이미지 접근
curl -I http://localhost/assets/products/1/550e8400-e29b-41d4-a716-446655440000.jpg
```

### 볼륨 데이터 유지 확인
```bash
# 컨테이너 재시작
docker-compose -f docker-compose-with-volumes.yml restart

# 이미지 파일이 여전히 존재하는지 확인
curl -I http://localhost/assets/products/1/550e8400-e29b-41d4-a716-446655440000.jpg

# 응답: 200 OK (파일이 유지됨)
```
