/**
 * 업종 프리셋 — 임계값을 "합리적 추정"에서 "근거 있는 기준"으로 격상시키는 층.
 *
 * 병합 순서 (뒤가 우선):
 *   기본값(DEFAULT/EXTERNAL_THRESHOLDS) < 업종 프리셋 < analysis-config.json < CLI 플래그
 *
 * - 프리셋 파일(src/common/presets/*.json)은 값마다 rationale(근거)을 함께 담는다 —
 *   심사·감리에서 "임계값 근거"를 물으면 파일이 답한다.
 * - analysis-config.json 에 "preset": "commerce" 로 상시 지정할 수도 있다 (플래그가 우선).
 * - 발화율 검증: bin/calibrate.mjs 가 코스피 대형주 코호트에 대해 신호별 발화율을 측정한다.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DEFAULT_THRESHOLDS } from './signals.mjs';
import { EXTERNAL_THRESHOLDS } from './dart-signals.mjs';

export const PRESETS_DIR = join(dirname(fileURLToPath(import.meta.url)), 'presets');

export function listPresets() {
  return readdirSync(PRESETS_DIR)
    .filter((f) => f.endsWith('.json') && f !== 'calibration-cohort.json')
    .map((f) => f.replace(/\.json$/, ''))
    .sort();
}

/** 프리셋 로드 — 없는 이름이면 사용 가능한 목록을 담아 throw. */
export function loadPreset(name) {
  const path = join(PRESETS_DIR, `${name}.json`);
  if (!existsSync(path)) {
    throw new Error(`알 수 없는 프리셋 "${name}" — 사용 가능: ${listPresets().join(', ')}`);
  }
  return JSON.parse(readFileSync(path, 'utf8'));
}

function readAnalysisConfig(dataDir) {
  if (!dataDir) return {};
  const path = join(dataDir, 'analysis-config.json');
  if (!existsSync(path)) return {};
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (e) {
    throw new Error(`analysis-config.json 파싱 실패 (${path}): ${e.message}`);
  }
}

const pick = (defaults, source) => {
  const out = {};
  for (const key of Object.keys(defaults)) {
    if (source && source[key] !== undefined) out[key] = Number(source[key]);
  }
  return out;
};

/**
 * 임계값 해석 — kind: 'internal'(S1~S4) | 'external'(E1~E5).
 * @param {{ dataDir?: string, preset?: string, kind: 'internal'|'external' }} opts
 * @returns {{ thresholds, presetUsed: string|null }}
 */
export function resolveThresholds({ dataDir, preset, kind }) {
  const defaults = kind === 'external' ? EXTERNAL_THRESHOLDS : DEFAULT_THRESHOLDS;
  const config = readAnalysisConfig(dataDir);
  const presetName = preset ?? config.preset ?? null; // 플래그 > config
  const presetBody = presetName ? loadPreset(presetName) : {};
  const presetSection = kind === 'external' ? presetBody.externalThresholds : presetBody.thresholds;
  const configSection = kind === 'external' ? config.externalThresholds : (config.thresholds ?? config);

  return {
    presetUsed: presetName,
    thresholds: {
      ...defaults,
      ...pick(defaults, presetSection),
      ...pick(defaults, configSection),
    },
  };
}
