#!/usr/bin/env node
/**
 * 엔게이지먼트 사이클 상태 CLI — "일회성 진단"을 "반복 컨설팅 엔진"으로 잇는 결정론 코어.
 *
 * 브리핑 전달 이후의 시간축을 관리한다:
 *   delivered → follow-up(권고 조치 이행 추적) → review(재진단 델타) → retro(환류) → 다음 사이클
 *
 * LLM 은 이 CLI 위에서만 움직인다(ceo-engagement-cycle 스킬이 관리자):
 * 상태 전이·게이트 판정·델타 계산은 전부 여기서 결정론으로 수행하고,
 * 스킬은 대화(이행 확인 인터뷰·후속 리포트 서술·회고)만 담당한다.
 *
 * 사용:
 *   init    --from <파이프라인 산출 폴더> [--dir <엔게이지먼트 루트>]
 *   status  --engagement <폴더>
 *   note    --engagement <폴더> --action <id> [--status pending|in-progress|done|blocked] [--note "..."]
 *   delta   --engagement <폴더> --packet <새 diagnostic-packet.json> [--json] [--out <delta.md>]
 *   advance --engagement <폴더> --to follow-up|review|retro|next-cycle
 *           (review 게이트: 전 액션 노트 1건+ / retro 게이트: --delta-file / next-cycle: --retro + --from)
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { parseBlocks, extractRiskSummary } from '../common/docx.mjs';

const argv = process.argv.slice(2);
const cmd = argv[0] && !argv[0].startsWith('-') ? argv[0] : undefined;
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};

const FOLLOW_UP_DAYS = 28; // 브리핑 전달 후 4주 — 다이어그램의 트래킹 창
const PHASES = ['delivered', 'follow-up', 'review', 'retro'];

function fail(msg) {
  console.error(msg);
  process.exit(1);
}

function usage() {
  return `engagement-cycle — 브리핑 전달 이후의 반복 컨설팅 사이클 상태 관리

  init    --from <파이프라인 산출 폴더> [--dir <엔게이지먼트 루트=engagements>]
  status  --engagement <폴더>
  note    --engagement <폴더> --action <id> [--status <s>] [--note "..."]
  delta   --engagement <폴더> --packet <새 packet.json> [--json] [--out <delta.md>]
  advance --engagement <폴더> --to <phase> [--delta-file <p>] [--retro <p>] [--from <새 산출 폴더>]`;
}

const today = () => new Date().toISOString().slice(0, 10);
const plusDays = (isoDate, days) => {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

function loadState(dir) {
  const p = join(dir, 'engagement.json');
  if (!existsSync(p)) fail(`engagement.json 이 없습니다: ${p} — 먼저 init 하세요`);
  return { path: p, state: JSON.parse(readFileSync(p, 'utf8')) };
}
const saveState = (path, state) => writeFileSync(path, JSON.stringify(state, null, 2), 'utf8');

/** 파이프라인 산출 폴더에서 baseline(패킷·브리핑)과 액션 리스트를 뽑는다. */
function baselineFrom(fromDir) {
  const dir = resolve(fromDir);
  const packetPath = join(dir, 'diagnostic-packet.json');
  const briefingPath = join(dir, 'briefing.md');
  if (!existsSync(packetPath)) fail(`diagnostic-packet.json 이 없습니다: ${dir}`);
  if (!existsSync(briefingPath)) fail(`briefing.md 가 없습니다: ${dir} — 브리핑까지 생성된 산출 폴더가 필요합니다`);
  const packet = JSON.parse(readFileSync(packetPath, 'utf8'));
  const briefing = readFileSync(briefingPath, 'utf8');
  const actions = extractRiskSummary(parseBlocks(briefing)).map((r, i) => ({
    id: i + 1,
    risk: r.title,
    confidence: r.confidence || null,
    action: r.action || null,
    status: 'pending',
    notes: [],
  }));
  return { dir, packetPath, briefingPath, packet, actions };
}

// ── init ─────────────────────────────────────────────────────
function cmdInit() {
  const from = flag('--from');
  if (!from) fail(usage());
  const base = baselineFrom(from);
  const company = base.packet.corp?.name ?? basename(base.dir);
  const slug = String(company).toLowerCase().replace(/[^0-9a-z가-힣]+/gi, '-').replace(/^-+|-+$/g, '') || 'company';
  const root = resolve(flag('--dir') ?? 'engagements');
  const engagementDir = join(root, slug);
  mkdirSync(engagementDir, { recursive: true });
  const statePath = join(engagementDir, 'engagement.json');
  if (existsSync(statePath)) fail(`이미 존재합니다: ${statePath} — 새 사이클은 advance --to next-cycle 로 잇습니다`);

  const deliveredAt = today();
  const state = {
    company,
    cycle: 1,
    phase: 'delivered',
    baseline: {
      sourceDir: base.dir, packetPath: base.packetPath, briefingPath: base.briefingPath, deliveredAt,
    },
    followUpDue: plusDays(deliveredAt, FOLLOW_UP_DAYS),
    actions: base.actions,
    history: [],
  };
  saveState(statePath, state);
  console.log(`엔게이지먼트 개설: ${engagementDir}`);
  console.log(`  고객: ${company} · cycle 1 · phase delivered · 이행 점검 만기 ${state.followUpDue}`);
  console.log(`  추적 액션 ${state.actions.length}건 (브리핑 권고 조치에서 파생)`);
  for (const a of state.actions) console.log(`   [${a.id}] ${a.risk} — ${a.action ?? '(권고 조치 없음)'}`);
  if (state.actions.length === 0) {
    console.log('  음성 브리핑(리스크 0건) — 이행 추적 없이 분기 재진단(review) 중심으로 운용합니다.');
  }
}

// ── status ───────────────────────────────────────────────────
function cmdStatus() {
  const { state } = loadState(flag('--engagement') ?? fail(usage()));
  console.log(`=== ${state.company} — cycle ${state.cycle} · phase ${state.phase} ===`);
  console.log(`브리핑 전달 ${state.baseline.deliveredAt} · 이행 점검 만기 ${state.followUpDue}${today() > state.followUpDue ? ' (지남 — follow-up/review 진행 필요)' : ''}`);
  for (const a of state.actions) {
    console.log(`[${a.status.padEnd(11)}] #${a.id} ${a.risk}`);
    console.log(`             조치: ${a.action ?? '-'} · 노트 ${a.notes.length}건${a.notes.length ? ` (최근: ${a.notes[a.notes.length - 1].note})` : ''}`);
  }
  if (state.history.length) {
    console.log(`지난 사이클 ${state.history.length}개: ${state.history.map((h) => `C${h.cycle}(${h.deliveredAt})`).join(', ')}`);
  }
}

// ── note ─────────────────────────────────────────────────────
function cmdNote() {
  const { path, state } = loadState(flag('--engagement') ?? fail(usage()));
  const id = Number(flag('--action'));
  const action = state.actions.find((a) => a.id === id);
  if (!action) fail(`액션 #${flag('--action')} 없음 — status 로 목록 확인`);
  const status = flag('--status');
  if (status) {
    if (!['pending', 'in-progress', 'done', 'blocked'].includes(status)) fail(`status 는 pending|in-progress|done|blocked: "${status}"`);
    action.status = status;
  }
  const note = flag('--note');
  if (note) action.notes.push({ date: today(), note });
  if (!status && !note) fail('--status 또는 --note 중 하나는 필요합니다');
  saveState(path, state);
  console.log(`#${id} ${action.risk} → ${action.status} (노트 ${action.notes.length}건)`);
}

// ── delta (재진단 패킷 비교 — review 의 결정론 코어) ─────────
export function computeDelta(beforePacket, afterPacket) {
  const index = (p) => new Map((p.signals ?? []).map((s) => [s.id, s]));
  const before = index(beforePacket);
  const after = index(afterPacket);
  const ids = [...new Set([...before.keys(), ...after.keys()])].sort();
  const rows = ids.map((id) => {
    const b = before.get(id);
    const a = after.get(id);
    const stateOf = (s) => (!s ? 'N/A' : !s.evaluable ? 'N/A' : s.present ? 'PRESENT' : 'absent');
    const evidenceDiff = [];
    for (const key of new Set([...Object.keys(b?.evidence ?? {}), ...Object.keys(a?.evidence ?? {})])) {
      const bv = b?.evidence?.[key];
      const av = a?.evidence?.[key];
      const scalar = (v) => v === null || ['number', 'string', 'boolean'].includes(typeof v);
      if (scalar(bv) && scalar(av) && String(bv) !== String(av)) evidenceDiff.push({ key, before: bv ?? null, after: av ?? null });
    }
    return {
      id,
      name: a?.name ?? b?.name ?? id,
      before: stateOf(b),
      after: stateOf(a),
      changed: stateOf(b) !== stateOf(a),
      evidenceDiff,
    };
  });
  return {
    newRisks: rows.filter((r) => r.after === 'PRESENT' && r.before !== 'PRESENT'),
    resolved: rows.filter((r) => r.before === 'PRESENT' && r.after !== 'PRESENT'),
    persisting: rows.filter((r) => r.before === 'PRESENT' && r.after === 'PRESENT'),
    rows,
  };
}

function renderDelta(state, delta, newPacketPath) {
  const lines = [`# ${state.company} — cycle ${state.cycle} 재진단 델타`, ''];
  lines.push(`기준: ${state.baseline.packetPath}`);
  lines.push(`재진단: ${resolve(newPacketPath)}`, '');
  const section = (title, rows) => {
    lines.push(`## ${title} (${rows.length}건)`);
    if (!rows.length) lines.push('- 없음');
    for (const r of rows) {
      lines.push(`- ${r.id} ${r.name}: ${r.before} → ${r.after}`);
      for (const d of r.evidenceDiff) lines.push(`  - ${d.key}: ${d.before} → ${d.after}`);
    }
    lines.push('');
  };
  section('신규 발화 — 즉시 브리핑 후보', delta.newRisks);
  section('해소 — 권고 조치 효과 검증 대상', delta.resolved);
  section('지속 — 이행 실패 또는 구조 문제', delta.persisting);
  lines.push('주의: 델타는 결정론 비교 결과다. 해소가 곧 "조치 덕분"은 아니며, 인과는 후속 리포트에서 이행 노트와 대조해 서술한다.');
  return lines.join('\n');
}

function cmdDelta() {
  const { state } = loadState(flag('--engagement') ?? fail(usage()));
  const packetPath = flag('--packet') ?? fail(usage());
  if (!existsSync(packetPath)) fail(`패킷 없음: ${packetPath}`);
  const before = JSON.parse(readFileSync(state.baseline.packetPath, 'utf8'));
  const after = JSON.parse(readFileSync(packetPath, 'utf8'));
  const delta = computeDelta(before, after);
  if (argv.includes('--json')) {
    console.log(JSON.stringify(delta, null, 2));
  } else {
    const text = renderDelta(state, delta, packetPath);
    const out = flag('--out');
    if (out) {
      writeFileSync(out, text, 'utf8');
      console.log(`델타 저장: ${out} (신규 ${delta.newRisks.length} · 해소 ${delta.resolved.length} · 지속 ${delta.persisting.length})`);
    } else {
      console.log(text);
    }
  }
}

// ── advance (게이트 검증 후 단계 전이 — 관리자의 유일한 전이 경로) ──
function cmdAdvance() {
  const { path, state } = loadState(flag('--engagement') ?? fail(usage()));
  const to = flag('--to') ?? fail(usage());
  const expectFrom = { 'follow-up': 'delivered', review: 'follow-up', retro: 'review', 'next-cycle': 'retro' };
  if (!(to in expectFrom)) fail(`--to 는 follow-up|review|retro|next-cycle: "${to}"`);
  if (state.phase !== expectFrom[to]) {
    fail(`전이 불가: 현재 phase 가 ${state.phase} — ${to} 는 ${expectFrom[to]} 에서만 진입합니다`);
  }

  if (to === 'review') {
    const missing = state.actions.filter((a) => a.notes.length === 0);
    if (missing.length) {
      fail(`review 게이트 실패 — 이행 노트 없는 액션 ${missing.length}건: ${missing.map((a) => `#${a.id}`).join(', ')}\n`
        + '모든 액션에 note 를 1건 이상 기록해야 재진단으로 넘어갑니다 (빈 추적은 만들지 않는다).');
    }
  }
  if (to === 'retro') {
    const deltaFile = flag('--delta-file');
    if (!deltaFile || !existsSync(deltaFile)) fail('retro 게이트 실패 — delta 산출물(--delta-file)이 필요합니다 (delta --out 으로 생성)');
    state.lastDeltaFile = resolve(deltaFile);
  }
  if (to === 'next-cycle') {
    const retroPath = flag('--retro');
    const from = flag('--from');
    if (!retroPath || !existsSync(retroPath)) fail('next-cycle 게이트 실패 — 회고 파일(--retro)이 필요합니다');
    if (!from) fail('next-cycle 게이트 실패 — 새 사이클 브리핑 산출 폴더(--from)가 필요합니다');
    const base = baselineFrom(from);
    state.history.push({
      cycle: state.cycle,
      deliveredAt: state.baseline.deliveredAt,
      baseline: state.baseline,
      actions: state.actions,
      deltaFile: state.lastDeltaFile ?? null,
      retroPath: resolve(retroPath),
    });
    const deliveredAt = today();
    state.cycle += 1;
    state.phase = 'delivered';
    state.baseline = { sourceDir: base.dir, packetPath: base.packetPath, briefingPath: base.briefingPath, deliveredAt };
    state.followUpDue = plusDays(deliveredAt, FOLLOW_UP_DAYS);
    state.actions = base.actions;
    delete state.lastDeltaFile;
    saveState(path, state);
    console.log(`cycle ${state.cycle} 개시 — phase delivered · 액션 ${state.actions.length}건 · 이행 점검 만기 ${state.followUpDue}`);
    return;
  }

  state.phase = to;
  saveState(path, state);
  console.log(`phase → ${to}`);
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
  else if (cmd === 'note') cmdNote();
  else if (cmd === 'delta') cmdDelta();
  else if (cmd === 'advance') cmdAdvance();
  else fail(usage());
}
