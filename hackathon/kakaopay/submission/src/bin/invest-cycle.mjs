#!/usr/bin/env node
/**
 * 투자 사이클 상태 CLI — invest-cycle 스킬(하네스)의 결정론 코어.
 *
 * 상태 전이·게이트 판정·주차 카운트·대장 기록은 전부 여기서 결정론으로 수행하고,
 * 스킬(LLM)은 대화(선별 서술·주간 점검 해석·회고)만 담당한다. 스킬이 state.json /
 * portfolio.json 을 직접 수정하는 것은 금지 — 전이는 이 CLI 로만 일어난다.
 * 게이트 실패는 exit 1 로 차단된다 (빈 단계 건너뛰기를 기계로 막는다).
 *
 * 상태머신: P1-select → P2-dryrun → P3-tracking(주 1회 check, periodWeeks 주) →
 *           P4-report → P5-retro → next-cycle(cycle+1, P1 재진입)
 *
 * 사용:
 *   init    --budget <원> [--weeks 4] [--interests "..."] [--dir <outputs/cycle>]
 *   status  [--dir]
 *   watch   --code <6자리> --name <이름> [--basis "선별 근거"] [--dir]     (P1 산출물)
 *   plan    --code <6자리> --name <이름> --entry <1차 매수가> --stop <손절가> --take <익절가>
 *           [--qty <수량>] [--basis "..."] [--dir]                          (P2 산출물)
 *   exclude --code <6자리> --name <이름> --reason "..." [--until "재검토 조건"] [--dir]
 *   check   [--summary "주간 점검 요약"] [--dir]                            (P3 전용 — 주차 +1)
 *   record  --summary "..." [--dir]                                         (현재 단계 이력 1줄)
 *   rule    --add "다음 사이클 규칙" [--dir]                                (P5 산출물)
 *   advance --to P2|P3|P4|P5|next-cycle [--report <result-*.md>] [--dir]
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_DIR = join(HERE, '..', '..', 'outputs', 'cycle');

const PHASES = ['P1-select', 'P2-dryrun', 'P3-tracking', 'P4-report', 'P5-retro'];
const PHASE_TODO = {
  'P1-select': '후보 스크리닝 (periodic-picks 위임) → watch 로 watchlist 기록',
  'P2-dryrun': '종목별 집행 체크리스트 확정 (plan_trade + buy-companion) → plan 으로 기록',
  'P3-tracking': '주 1회 점검 (손절선·비중 20% 상한·악재) → check 로 주차 기록',
  'P4-report': '사이클 리포트를 outputs/result-<날짜>.md 로 저장 → advance --to P5 --report <경로>',
  'P5-retro': '행동 패턴 복기 (trade-retrospective 위임) → rule --add 로 규칙 기록',
};

const argv = process.argv.slice(2);
const cmd = argv[0] && !argv[0].startsWith('-') ? argv[0] : undefined;
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};

function fail(msg) {
  console.error(msg);
  process.exit(1);
}

function usage() {
  return `invest-cycle — 투자 사이클 상태 관리 (전이는 이 CLI 로만, 게이트 실패 시 exit 1)

  init    --budget <원> [--weeks 4] [--interests "..."] [--dir <경로>]
  status  [--dir]
  watch   --code <6자리> --name <이름> [--basis "..."] [--dir]
  plan    --code <6자리> --name <이름> --entry <가> --stop <가> --take <가> [--qty <수>] [--basis "..."] [--dir]
  exclude --code <6자리> --name <이름> --reason "..." [--until "..."] [--dir]
  check   [--summary "..."] [--dir]
  record  --summary "..." [--dir]
  rule    --add "..." [--dir]
  advance --to P2|P3|P4|P5|next-cycle [--report <경로>] [--dir]`;
}

const today = () => new Date().toISOString().slice(0, 10);
const cycleDir = () => resolve(flag('--dir') ?? DEFAULT_DIR);
const statePath = () => join(cycleDir(), 'state.json');
const portfolioPath = () => join(cycleDir(), 'portfolio.json');

function loadJson(path, label) {
  if (!existsSync(path)) fail(`${label} 이 없습니다: ${path} — 먼저 init 하세요`);
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return fail(`${label} 이 손상되었습니다: ${path} — 백업 후 init 으로 재생성하세요`);
  }
}
const loadState = () => loadJson(statePath(), 'state.json');
const loadPortfolio = () => loadJson(portfolioPath(), 'portfolio.json');
const save = (path, data) => writeFileSync(path, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
const saveState = (state) => save(statePath(), state);
const savePortfolio = (p) => { p.updatedAt = today(); save(portfolioPath(), p); };

function pushHistory(state, summary) {
  state.history.push({ date: today(), phase: state.phase, summary });
}

// 규칙은 사이클 태그와 함께 저장한다 — 상한(사이클당 2개)과 next-cycle 게이트(이번
// 사이클 규칙 ≥ 1)를 "이번 사이클" 기준으로 판정하기 위함. 누적 길이로 판정하면
// cycle 2 부터 게이트가 무력화되고(승계 규칙이 이미 ≥1), 상한은 영구 잠금이 된다.
const ruleText = (r) => (typeof r === 'string' ? r : r.text);   // 구버전(평문 배열) 하위호환
const rulesOfCycle = (state) => state.rules.filter((r) => typeof r !== 'string' && r.cycle === state.cycle);

function requireCode() {
  const code = String(flag('--code') ?? '').trim();
  if (!/^\d{6}$/.test(code)) fail(`--code 는 6자리 숫자여야 합니다 — got "${code}"`);
  const name = String(flag('--name') ?? '').trim();
  if (!name) fail('--name 이 필요합니다');
  return { code, name };
}

// ── init ─────────────────────────────────────────────────────
function cmdInit() {
  const budget = Number(flag('--budget'));
  if (!Number.isInteger(budget) || budget <= 0) fail('--budget <원> 이 필요합니다 (양의 정수) — "잃어도 생활에 지장 없는 돈"인지 사용자에게 먼저 확인');
  const weeks = Number(flag('--weeks') ?? 4);
  if (!Number.isInteger(weeks) || weeks < 1 || weeks > 52) fail('--weeks 는 1~52 사이 정수');
  if (existsSync(statePath())) fail(`이미 존재합니다: ${statePath()} — 새 사이클은 advance --to next-cycle 로 잇습니다`);

  mkdirSync(cycleDir(), { recursive: true });
  const state = {
    cycle: 1,
    phase: 'P1-select',
    week: 0,
    budget,
    periodWeeks: weeks,
    interests: flag('--interests') ?? null,
    startedAt: today(),
    lastCheckAt: null,
    nextAction: PHASE_TODO['P1-select'],
    rules: [],
    history: [{ date: today(), phase: 'P0', summary: `사이클 개설 — 예산 ${budget.toLocaleString('ko-KR')}원, 주기 ${weeks}주` }],
  };
  save(statePath(), state);
  save(portfolioPath(), { updatedAt: today(), holdings: [], watchlist: [], excluded: [] });
  console.log(`사이클 개설: ${cycleDir()}`);
  console.log(`  cycle 1 · phase P1-select · 예산 ${budget.toLocaleString('ko-KR')}원 · 주기 ${weeks}주`);
  console.log(`  다음 할 일: ${state.nextAction}`);
}

// ── status ───────────────────────────────────────────────────
function cmdStatus() {
  const state = loadState();
  const portfolio = loadPortfolio();
  const weekLabel = state.phase === 'P3-tracking' ? ` · 주차 ${state.week}/${state.periodWeeks}` : '';
  console.log(`=== 사이클 ${state.cycle} · 단계 ${state.phase}${weekLabel} ===`);
  console.log(`예산 ${state.budget.toLocaleString('ko-KR')}원 · 시작 ${state.startedAt} · 최근 점검 ${state.lastCheckAt ?? '-'}`);
  console.log(`오늘 할 일: ${state.nextAction}`);
  console.log(`watchlist ${portfolio.watchlist.length}종목 · holdings ${portfolio.holdings.length}종목 · excluded ${portfolio.excluded.length}종목 · 규칙 ${state.rules.length}개`);
  for (const h of portfolio.holdings) {
    console.log(`  [${h.status}] ${h.name}(${h.code}) — 손절 ${h.plan?.stopLoss ?? '-'} · 익절 ${h.plan?.takeProfit ?? '-'}`);
  }
  for (const r of state.rules) console.log(`  규칙: ${ruleText(r)}`);
  if (state.history.length) {
    const last = state.history[state.history.length - 1];
    console.log(`최근 이력: [${last.date} ${last.phase}] ${last.summary}`);
  }
}

// ── watch / plan / exclude (대장 기록) ───────────────────────
function cmdWatch() {
  const state = loadState();
  const portfolio = loadPortfolio();
  const { code, name } = requireCode();
  if (portfolio.watchlist.some((w) => w.code === code)) fail(`이미 watchlist 에 있습니다: ${name}(${code})`);
  portfolio.watchlist.push({ code, name, basis: flag('--basis') ?? null, cycle: state.cycle });
  savePortfolio(portfolio);
  pushHistory(state, `watchlist 추가: ${name}(${code})`);
  saveState(state);
  console.log(`watchlist + ${name}(${code}) — 총 ${portfolio.watchlist.length}종목`);
}

function cmdPlan() {
  const state = loadState();
  const portfolio = loadPortfolio();
  const { code, name } = requireCode();
  const entry = Number(flag('--entry'));
  const stop = Number(flag('--stop'));
  const take = Number(flag('--take'));
  if (![entry, stop, take].every((n) => Number.isFinite(n) && n > 0)) {
    fail('--entry/--stop/--take (1차 매수가·손절가·익절가) 가 모두 필요합니다 — "팔 기준 없이 사지 않는다"');
  }
  if (stop >= entry) fail(`손절가(${stop})는 1차 매수가(${entry})보다 낮아야 합니다`);
  if (take <= entry) fail(`익절가(${take})는 1차 매수가(${entry})보다 높아야 합니다`);
  const existing = portfolio.holdings.find((h) => h.code === code);
  const holding = existing ?? { code, name, status: 'planned', cycle: state.cycle };
  holding.plan = { entries: [entry], stopLoss: stop, takeProfit: take, qty: Number(flag('--qty')) || null };
  holding.basis = flag('--basis') ?? holding.basis ?? null;
  if (!existing) portfolio.holdings.push(holding);
  portfolio.watchlist = portfolio.watchlist.filter((w) => w.code !== code);
  savePortfolio(portfolio);
  pushHistory(state, `주문 계획 확정: ${name}(${code}) 1차 ${entry} · 손절 ${stop} · 익절 ${take}`);
  saveState(state);
  console.log(`plan 기록: ${name}(${code}) — 1차 ${entry} / 손절 ${stop} / 익절 ${take} (실제 주문은 사용자 몫)`);
}

function cmdExclude() {
  const state = loadState();
  const portfolio = loadPortfolio();
  const { code, name } = requireCode();
  const reason = flag('--reason');
  if (!reason) fail('--reason 이 필요합니다 — "왜 뺐는지"가 다음 사이클의 자산이다');
  portfolio.excluded.push({ code, name, reason, until: flag('--until') ?? null, cycle: state.cycle });
  portfolio.watchlist = portfolio.watchlist.filter((w) => w.code !== code);
  savePortfolio(portfolio);
  pushHistory(state, `제외: ${name}(${code}) — ${reason}`);
  saveState(state);
  console.log(`excluded + ${name}(${code}): ${reason}`);
}

// ── check (P3 주간 점검 — 주차의 유일한 증가 경로) ───────────
function cmdCheck() {
  const state = loadState();
  if (state.phase !== 'P3-tracking') fail(`check 는 P3-tracking 에서만 — 현재 ${state.phase}`);
  state.week += 1;
  state.lastCheckAt = today();
  const summary = flag('--summary') ?? '주간 점검 (손절선·비중·악재 이상 없음)';
  pushHistory(state, `주간 점검 ${state.week}/${state.periodWeeks}: ${summary}`);
  state.nextAction = state.week >= state.periodWeeks
    ? '추적 완료 — advance --to P4 로 리포트 단계 진입'
    : `다음 주간 점검 (${state.week + 1}/${state.periodWeeks}): 손절선·비중 20% 상한·악재 확인`;
  saveState(state);
  console.log(`주차 ${state.week}/${state.periodWeeks} 기록 — ${state.nextAction}`);
}

// ── record / rule ────────────────────────────────────────────
function cmdRecord() {
  const state = loadState();
  const summary = flag('--summary');
  if (!summary) fail('--summary 가 필요합니다');
  pushHistory(state, summary);
  saveState(state);
  console.log(`이력 기록 [${state.phase}]: ${summary}`);
}

function cmdRule() {
  const state = loadState();
  const rule = flag('--add');
  if (!rule) fail('--add "규칙" 이 필요합니다');
  if (rulesOfCycle(state).length >= 2) {
    fail('이번 사이클 규칙은 2개까지 — "규칙이 많으면 아무것도 안 지켜진다" (trade-retrospective)');
  }
  state.rules.push({ cycle: state.cycle, text: rule });
  pushHistory(state, `규칙 추가: ${rule}`);
  saveState(state);
  console.log(`규칙 ${state.rules.length}개 (이번 사이클 ${rulesOfCycle(state).length}개): ${rule}`);
}

// ── advance (게이트 검증 후 전이 — 유일한 전이 경로) ─────────
function cmdAdvance() {
  const state = loadState();
  const portfolio = loadPortfolio();
  const to = flag('--to') ?? fail(usage());
  const expectFrom = {
    P2: 'P1-select', P3: 'P2-dryrun', P4: 'P3-tracking', P5: 'P4-report', 'next-cycle': 'P5-retro',
  };
  if (!(to in expectFrom)) fail(`--to 는 P2|P3|P4|P5|next-cycle: "${to}"`);
  if (state.phase !== expectFrom[to]) {
    fail(`전이 불가: 현재 단계가 ${state.phase} — ${to} 는 ${expectFrom[to]} 에서만 진입합니다 (단계 건너뛰기 금지)`);
  }

  if (to === 'P2' && portfolio.watchlist.length === 0 && portfolio.holdings.length === 0) {
    fail('P2 게이트 실패 — watchlist 가 비어 있습니다. periodic-picks 선별 결과를 watch 로 먼저 기록하세요');
  }
  if (to === 'P3') {
    const planned = portfolio.holdings.filter((h) => h.plan?.stopLoss > 0 && h.plan?.takeProfit > 0);
    if (planned.length === 0) {
      fail('P3 게이트 실패 — 손절·익절가가 확정된 holdings 가 없습니다. plan 으로 먼저 기록하세요 ("팔 기준 없이 사지 않는다")');
    }
  }
  if (to === 'P4' && state.week < state.periodWeeks) {
    fail(`P4 게이트 실패 — 추적 ${state.week}/${state.periodWeeks}주차. check 로 주간 점검을 채워야 리포트로 넘어갑니다 (빈 추적을 만들지 않는다)`);
  }
  if (to === 'P5') {
    const report = flag('--report');
    if (!report || !existsSync(report)) fail('P5 게이트 실패 — 사이클 리포트 파일(--report <outputs/result-*.md>)이 필요합니다');
    state.lastReportPath = resolve(report);
  }
  if (to === 'next-cycle') {
    // "이번 사이클에서" 회고한 규칙이 있어야 한다 — 지난 사이클 승계분으로는 통과 불가
    if (rulesOfCycle(state).length === 0) {
      fail('next-cycle 게이트 실패 — 이번 사이클 회고 규칙이 0개입니다. rule --add 로 이번 사이클 규칙을 1개 이상 기록하세요 (지난 사이클 규칙 승계만으로는 회고를 건너뛸 수 없다)');
    }
    pushHistory(state, `사이클 ${state.cycle} 종료 — 이번 사이클 규칙 ${rulesOfCycle(state).length}개 (누적 ${state.rules.length}개), 리포트 ${state.lastReportPath ?? '-'}`);
    state.cycle += 1;
    state.phase = 'P1-select';
    state.week = 0;
    state.startedAt = today();
    delete state.lastReportPath;
    state.nextAction = PHASE_TODO['P1-select'];
    saveState(state);
    console.log(`사이클 ${state.cycle} 개시 — phase P1-select (규칙 ${state.rules.length}개 승계, 13사이클마다 연 단위 전체 재구성)`);
    return;
  }

  state.phase = PHASES[PHASES.indexOf(state.phase) + 1];
  state.nextAction = PHASE_TODO[state.phase];
  pushHistory(state, `단계 전이 → ${state.phase}`);
  saveState(state);
  console.log(`phase → ${state.phase}`);
  console.log(`다음 할 일: ${state.nextAction}`);
}

// ── 디스패치 ─────────────────────────────────────────────────
const isMain = process.argv[1] && import.meta.url.endsWith(basename(process.argv[1]));
if (isMain) {
  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(usage());
    process.exit(0);
  } else if (!cmd) {
    console.log(usage());
    process.exit(1);
  } else if (cmd === 'init') cmdInit();
  else if (cmd === 'status') cmdStatus();
  else if (cmd === 'watch') cmdWatch();
  else if (cmd === 'plan') cmdPlan();
  else if (cmd === 'exclude') cmdExclude();
  else if (cmd === 'check') cmdCheck();
  else if (cmd === 'record') cmdRecord();
  else if (cmd === 'rule') cmdRule();
  else if (cmd === 'advance') cmdAdvance();
  else fail(usage());
}
