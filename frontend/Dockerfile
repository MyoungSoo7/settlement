# Multi-stage build for React frontend
# Stage 1: Build
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install ALL dependencies including devDependencies (typescript, vite, etc.)
# Builder stage needs devDependencies to run `tsc && vite build`
RUN npm ci

# Copy source code
COPY . .

# Build-time env vars (override .env.production)
ARG VITE_API_BASE_URL
ARG VITE_TOSS_CLIENT_KEY
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_TOSS_CLIENT_KEY=$VITE_TOSS_CLIENT_KEY

# Build for production
RUN npm run build

# Stage 2: Production with Nginx
# 런타임 베이스. 1.25-alpine 은 Alpine 3.19.1 을 물고 있었는데 그 배포판은 지원이 끝나 보안
# 업데이트가 오지 않았고, 이미지 스캔 게이트가 CRITICAL 3건으로 막았다
# (libexpat CVE-2024-45491·45492, libxml2 CVE-2024-56171 — 셋 다 상위 Alpine 에 수정본 존재).
# 태그를 올리는 것이 정답이다(스캔을 끄는 것이 아니라 — ci.yml 의 게이트 주석과 같은 판단).
FROM nginx:1.31-alpine

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/

# Copy built app from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Expose port
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
