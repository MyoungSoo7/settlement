/**
 * 견고한 CSV 파서 (zero-dependency).
 *
 * 실제 회사 데이터(ERP/엑셀 내보내기)를 그대로 받기 위한 최소 요건:
 * - 따옴표 필드("서울, 마포구"), 이스케이프 따옴표("" → ")
 * - UTF-8 BOM, CRLF/LF 혼용, 트레일링 빈 줄
 * - 셀 좌우 공백 트리밍
 */

/** CSV 한 줄을 셀 배열로 분해한다 (따옴표 규칙 지원). */
export function parseCsvLine(line) {
  const cells = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (inQuotes) {
      if (ch === '"') {
        if (line[i + 1] === '"') { cur += '"'; i += 1; } else { inQuotes = false; }
      } else {
        cur += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      cells.push(cur.trim());
      cur = '';
    } else {
      cur += ch;
    }
  }
  cells.push(cur.trim());
  return cells;
}

/** CSV 전문을 헤더 기준 객체 배열로 파싱한다. */
export function parseCsv(text) {
  const clean = text.replace(/^﻿/, '').trim();
  if (!clean) return { columns: [], rows: [] };
  const [headerLine, ...lines] = clean.split(/\r?\n/);
  const columns = parseCsvLine(headerLine);
  const rows = lines
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      const cells = parseCsvLine(line);
      return Object.fromEntries(columns.map((c, i) => [c, cells[i] ?? '']));
    });
  return { columns, rows };
}

/** 숫자 파싱 — 천단위 콤마·통화기호·전각 마이너스 허용. 실패 시 라벨 포함 오류. */
export function num(value, label) {
  const normalized = String(value ?? '')
    .replace(/[,\s₩원]/g, '')
    .replace(/^[−▲△]/, '-')          // 전각 마이너스·회계 세모 표기
    .replace(/^\((.+)\)$/, '-$1');    // 회계 표기 (1000) = -1000
  const n = Number(normalized);
  if (!Number.isFinite(n)) throw new Error(`${label} 가 숫자가 아님: "${value}"`);
  return n;
}
