/**
 * invest-copilot guard rules — 투자 표현 컴플라이언스 + 조회 DB 보호 정규식 1차 검사.
 * check-write.mjs(에이전트 훅), pre-commit.mjs(git 폴백) 가 공유한다.
 *
 * 반환 violation: { rule, severity: 'BLOCK'|'WARN', line, message }
 */

// 투자 관련 텍스트로 볼 파일 — 문서/리포트류만 검사 (코드 파일의 문자열 상수는 제외)
export function isAdviceScope(filePath) {
  const p = filePath.replace(/\\/g, '/');
  // 컴플라이언스 룰북 자체(금지어 정의 표)는 검사 제외 —
  // 린터가 자기 룰 정의를 위반으로 잡는 false positive 방지.
  if (/skills\/compliance-language\/SKILL\.md$/.test(p)) return false;
  return /\.(md|txt|html)$/i.test(p);
}

// 금지 표현 — compliance-language skill 의 표와 동기 유지
const FORBIDDEN = [
  { re: /수익\s*(을|이)?\s*보장/, label: '수익 보장' },
  { re: /원금\s*(을|이)?\s*보장/, label: '원금 보장' },
  { re: /무조건\s*(오른|오릅|수익|먹)/, label: '무조건 오른다/수익' },
  { re: /확실히\s*(오른|오릅|수익)/, label: '확실히 오른다' },
  { re: /100\s*%\s*(수익|확실|오른)/, label: '100% 수익/확실' },
  { re: /(리스크|손실|위험)\s*(가|이)?\s*없는\s*(투자|종목|상품)/, label: '리스크 없는 투자' },
  { re: /guaranteed\s+(profit|return)s?/i, label: 'guaranteed profit/return' },
];

export function checkFileContent(filePath, content) {
  const violations = [];
  const lines = content.split('\n');
  const push = (rule, severity, i, message) =>
    violations.push({ rule, severity, line: i + 1, message });

  if (isAdviceScope(filePath)) {
    lines.forEach((raw, i) => {
      const line = raw.trim();
      // 인용/금지목록 문맥은 제외 — "쓰지 말 것" 표를 문서화할 수 있어야 한다
      if (line.startsWith('>') || /금지|쓰지 말|X\b|❌/.test(line)) return;
      for (const { re, label } of FORBIDDEN) {
        if (re.test(line)) {
          push('forbidden-claims-guard', 'BLOCK', i,
            `보장/단정 표현("${label}") — 자본시장법상 금지 표현입니다. 기준 충족 여부 서술로 바꾸세요 (compliance-language skill).`);
        }
      }
      if (/목표\s*주가/.test(line) && !/산출\s*(하지|금지)|없/.test(line)) {
        push('no-price-data-guard', 'WARN', i,
          '목표주가 언급 — 이 플랫폼에는 주가 데이터가 없어 목표주가를 산출할 수 없습니다.');
      }
    });

    // 매수/매도 판단이 담긴 문서인데 고지문이 없으면 경고
    const advisory = /(매수|매도|BUY-8|SELL-7)/.test(content);
    const hasDisclaimer = /투자자문.*아닙|투자\s*판단.*책임/s.test(content);
    if (advisory && !hasDisclaimer) {
      violations.push({ rule: 'disclaimer-guard', severity: 'WARN', line: 0,
        message: '매수/매도 판단이 담긴 문서에 필수 고지문이 없습니다 (compliance-language skill 참조).' });
    }
  }

  return violations;
}

export function checkCommand(command) {
  const violations = [];
  const push = (rule, severity, message) => violations.push({ rule, severity, line: 0, message });

  // 조회 서비스 DB 직접 쓰기 차단 (수집 데이터 정정은 각 서비스 admin 배치 경로로만)
  if (/\b(psql|pgcli|pg_dump)\b/.test(command)
      && /(lemuel_financial|lemuel_economics|lemuel_company)/.test(command)
      && /\b(UPDATE|DELETE|INSERT|TRUNCATE|ALTER)\s/i.test(command)) {
    push('readonly-db-guard', 'BLOCK',
      'financial/economics/company 조회 DB 에 직접 쓰기 시도 — 데이터 정정은 각 서비스의 수집 배치(admin API)로만. 조회는 invest-copilot MCP 도구를 사용하세요.');
  }
  return violations;
}

export function formatViolations(violations) {
  return violations
    .map(v => `[${v.severity}] ${v.rule}${v.line ? ` (line ${v.line})` : ''}: ${v.message}`)
    .join('\n');
}
