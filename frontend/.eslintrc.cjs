/**
 * ESLint 설정 — eslintrc 포맷(설치된 ESLint 8.57 + @typescript-eslint 6 기준).
 *
 * 타입 인지(type-aware) 규칙은 켜지 않는다: `parserOptions.project` 를 걸면 tsconfig 에서 제외된
 * 파일(e2e·테스트·설정 스크립트)이 전부 파싱 오류가 나고, 린트가 typecheck 를 중복 수행해 느려진다.
 * 타입 검증은 `npm run typecheck`(tsc) 가 정본이고, 여기서는 tsc 가 못 잡는 축(훅 규칙·미사용
 * 심볼·명백한 실수)만 본다.
 *
 * package.json 이 `"type": "module"` 이라 CommonJS 설정 파일은 확장자가 `.cjs` 여야 한다.
 */
module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  plugins: ['@typescript-eslint', 'react-refresh'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: [
    'dist',
    'coverage',
    'node_modules',
    'public',
    '.omc',
    'e2e/__screenshots__',
  ],
  rules: {
    // Vite HMR 경계 — 컴포넌트 파일에서 상수 외 값을 함께 export 하면 갱신이 깨진다.
    'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    // `_` 접두 인자는 의도적 미사용(콜백 시그니처 맞추기)이므로 통과시킨다.
    '@typescript-eslint/no-unused-vars': [
      'error',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
    ],
    // 기존 부채라 error → warn 으로 낮춘다(recommended 기본값은 error).
    // 도입 당시 83건 → catch 절 부채(72건)는 `@/lib/apiError` 헬퍼로 정리 완료, 남은 11건은
    // 성격이 다르다: TossPayments SDK 전역 캐스팅(4)·정산 필터 value(3)·테스트 목 캐스팅(4).
    // 이것들까지 걷어내면 이 줄을 지워 recommended 의 error 로 복귀시킨다.
    '@typescript-eslint/no-explicit-any': 'warn',
  },
  overrides: [
    {
      // Node 컨텍스트에서 도는 것들 — 빌드·테스트 설정, 일회성 스크립트, Playwright e2e.
      files: [
        '*.cjs',
        '*.config.js',
        '*.config.ts',
        'e2e/**/*.ts',
        'playwright.config.ts',
        'repro-settlement.mjs',
      ],
      env: { node: true, browser: false },
      rules: {
        '@typescript-eslint/no-var-requires': 'off',
      },
    },
    {
      // 유닛 테스트 — jsdom + node 유틸을 함께 쓴다.
      files: ['src/__tests__/**/*.{ts,tsx}', '**/*.test.{ts,tsx}'],
      env: { node: true },
    },
  ],
};
