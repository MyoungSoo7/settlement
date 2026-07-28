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
    // no-explicit-any 는 recommended 의 기본값(error)을 그대로 쓴다 — 도입 당시 83건이던 부채를
    // 전부 정리했다(catch 절은 @/lib/apiError, 토스 전역은 types/tosspayments.d.ts,
    // 정산 필터는 제네릭 키, 인터셉터 테스트는 최소 타입). 다시 warn 으로 낮추지 말 것.
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
