/**
 * settlement-copilot guard rules — 파일 내용/명령어에 대한 정규식 1차 검사.
 * check-write.mjs(에이전트 훅), pre-commit.mjs(git 폴백) 가 공유한다.
 *
 * 반환 violation: { rule, severity: 'BLOCK'|'WARN', line, message }
 */

const MONEY_WORD = '(amount|fee|commission|holdback|payout|balance|total|price|krw|money|정산|수수료)';

// 금액 스코프 판단 — 정산/원장/지급/대출/결제 패키지의 Java 파일만 money 규칙 적용
export function isMoneyScope(filePath) {
  return /\.(java|kt)$/.test(filePath)
    && /(settlement|ledger|payout|chargeback|loan|payment|recon)/i.test(filePath);
}

export function checkFileContent(filePath, content) {
  const violations = [];
  const lines = content.split('\n');
  const path = filePath.replace(/\\/g, '/');

  const push = (rule, severity, i, message) =>
    violations.push({ rule, severity, line: i + 1, message });

  lines.forEach((raw, i) => {
    const line = raw.trim();
    if (line.startsWith('//') || line.startsWith('*') || line.startsWith('#')) return;

    // ── money-type-guard ──────────────────────────────────────────────
    if (isMoneyScope(path)) {
      const floatDecl = new RegExp(`\\b(float|double|Float|Double)\\s+\\w*${MONEY_WORD}\\w*`, 'i');
      const floatParse = new RegExp(`${MONEY_WORD}\\w*\\s*=[^=].*\\.(parseDouble|parseFloat|doubleValue|floatValue)\\s*\\(`, 'i');
      const bdFromDouble = /new\s+BigDecimal\s*\(\s*\d+\.\d+\s*\)/;
      if (floatDecl.test(line) || floatParse.test(line)) {
        push('money-type-guard', 'BLOCK', i,
          '금액을 float/double 로 다루고 있습니다. BigDecimal + RoundingMode.HALF_UP 을 사용하세요.');
      }
      if (bdFromDouble.test(line)) {
        push('money-type-guard', 'BLOCK', i,
          'new BigDecimal(double 리터럴) 은 정밀도 오염 — new BigDecimal("문자열") 로 생성하세요.');
      }
      if (/\.divide\s*\([^,)]*\)\s*[;.]/.test(line)) {
        push('money-rounding-guard', 'WARN', i,
          'RoundingMode 없는 divide — ArithmeticException 위험. divide(x, scale, RoundingMode.HALF_UP) 형태로.');
      }
    }

    // ── immutable-history-guard ───────────────────────────────────────
    if (/UPDATE\s+(settlements|ledger_entries|payouts|settlement_adjustments)\b/i.test(line)
        && !/settlement_status|retry|outbox/i.test(line)) {
      push('immutable-history-guard', 'BLOCK', i,
        '정산/원장/지급 레코드 UPDATE 감지 — 정정은 조정(adjustment)/역분개 레코드 추가로만 합니다 (ADR 0004/0007).');
    }
    if (/DELETE\s+FROM\s+(settlements|ledger_entries|payouts|settlement_adjustments)\b/i.test(line)) {
      push('immutable-history-guard', 'BLOCK', i,
        '정산/원장/지급 레코드 DELETE 감지 — 삭제는 어떤 경우에도 금지입니다.');
    }
    if (/setCommissionRate\s*\(/.test(line)) {
      push('immutable-history-guard', 'BLOCK', i,
        'commission_rate 는 정산 시점 스냅샷 — 생성 후 변경 금지 (V32 이력 보존 원칙).');
    }

    // ── pii-logging-guard ─────────────────────────────────────────────
    if (/\blog(ger)?\.(info|debug|warn|error|trace)\s*\(/.test(line)
        && /(accountN(o|umber)|bankAccount|residentN(o|umber)|ssn|cardN(o|umber)|계좌|주민)/i.test(line)
        && !/mask/i.test(line)) {
      push('pii-logging-guard', 'BLOCK', i,
        '계좌/주민/카드번호가 로그에 평문으로 들어갑니다 — common.audit 마스킹 유틸을 경유하세요.');
    }
  });

  // ── migration-guard (파일 단위) ─────────────────────────────────────
  const mig = path.match(/db\/migration\/(V[^/]+)\.sql$/);
  if (mig) {
    if (!/^V\d{14}__/.test(mig[1]) && !/^V\d{1,3}__/.test(mig[1])) {
      violations.push({ rule: 'migration-guard', severity: 'WARN', line: 0,
        message: `마이그레이션 파일명 규칙 위반: ${mig[1]} — 신규는 V{timestamp}__ (예: V20260706120000__) 권장.` });
    }
    lines.forEach((raw, i) => {
      if (/DROP\s+(TABLE|COLUMN)/i.test(raw) && !/IF\s+EXISTS.*_tmp|_backup/i.test(raw)) {
        violations.push({ rule: 'migration-guard', severity: 'WARN', line: i + 1,
          message: '파괴적 DDL(DROP) — 정산 데이터 보존 연한 확인 후 expand-contract 절차로 진행하세요.' });
      }
    });
  }

  return violations;
}

export function checkCommand(command) {
  const violations = [];
  const push = (rule, severity, message) => violations.push({ rule, severity, line: 0, message });

  // prod-db-guard: 정산 계열 DB 직접 접속
  if (/\b(psql|pgcli|pg_dump)\b/.test(command)
      && /(opslab|settlement_db|lemuel_loan)/.test(command)
      && /(UPDATE|DELETE|INSERT|TRUNCATE|ALTER)/i.test(command)) {
    push('prod-db-guard', 'BLOCK',
      '정산 계열 DB 에 직접 쓰기 시도 — 데이터 정정은 adjustment/역분개 API 경로로만. 조회는 MCP 도구(recon_run, ledger_entries)를 사용하세요.');
  }
  if (/kubectl\s+exec/.test(command) && /(psql|pg_dump)/.test(command)) {
    push('prod-db-guard', 'BLOCK',
      '운영 파드에서 DB 직접 접속 시도 — MCP 도구 또는 승인된 러너북 절차를 사용하세요.');
  }
  // 이벤트 직접 produce (멱등 우회 위험)
  if (/(rpk\s+topic\s+produce|kafka-console-producer)/.test(command) && /lemuel\./.test(command)) {
    push('event-produce-guard', 'WARN',
      '운영 토픽에 직접 produce — Outbox 를 우회하면 event_id 멱등 체계가 깨질 수 있습니다. 테스트 목적이면 -z none 및 테스트 토픽을 사용하세요.');
  }
  return violations;
}

export function formatViolations(violations) {
  return violations
    .map(v => `[${v.severity}] ${v.rule}${v.line ? ` (line ${v.line})` : ''}: ${v.message}`)
    .join('\n');
}
