import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-axios': ['axios'],
        },
      },
    },
    chunkSizeWarningLimit: 800,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/__tests__/setup.ts'],
    exclude: ['node_modules/**', 'dist/**', 'e2e/**'],
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
    coverage: {
      // 라인 90% — 백엔드 JaCoCo 게이트(LINE 0.90)와 같은 기준선을 프론트에도 건다.
      //
      // CI 의 필수 체크 `Frontend - Tests` 가 `npm run test:coverage` 를 돌리므로 이 임계치가
      // 곧 게이트다. 즉 **테스트 없이 들어오는 새 화면·분기는 PR 에서 막힌다** — 그게 목적이다.
      //
      // ⚠️ 현재 여유가 크지 않다(2026-08-14 기준 90.04%, 약 1줄). 기능을 추가하면서 커버리지가
      // 떨어지면 이 게이트가 먼저 운다. 임계치를 내리는 것으로 대응하지 말 것 — 그러면 게이트가
      // 숫자만 남고 의미가 사라진다. 새 코드에 테스트를 붙이는 쪽이 정답이다.
      //
      // lines 만 거는 이유: statements(87.8%)·branches(82.4%)는 아직 90 에 못 미친다. 지금 함께
      // 걸면 곧바로 빨간 게이트가 되어 아무도 신뢰하지 않게 된다 — 올라간 축부터 잠근다.
      thresholds: { lines: 90 },
    },
  },
  server: {
    port: 3000,
    proxy: {
      // market-stream-service (8110, Go) — 실시간 시세 SSE. dev 는 게이트웨이 없이 직결하므로
      // 프리픽스를 벗겨 서비스 실경로(/stream/{code})로 전달. /api 보다 먼저 선언해야 우선 매칭.
      '/api/market-stream': {
        target: 'http://localhost:8110',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api\/market-stream/, ''),
      },
      // ai-service (8096) — /api 보다 먼저 선언해야 우선 매칭 (SSE 스트리밍 포함)
      '/api/ai': {
        target: 'http://localhost:8096',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/admin': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/auth': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/games': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
    },
  },
})
