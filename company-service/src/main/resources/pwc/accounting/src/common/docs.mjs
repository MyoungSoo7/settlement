/**
 * 비정형 문서 축 (docs) — 계약서 요약·이사회 의사록 등 텍스트 문서를 "신뢰하지 않는 데이터"로
 * 받아들이는 층. 두 가지 일을 한다:
 *
 * 1. 문서 인벤토리: --docs-dir 의 .md/.txt 를 읽어 에이전트가 판별 테스트를 구체화할
 *    참고 자료로 제공한다 (문서 내용은 지시가 아니라 추출 대상 데이터).
 * 2. 지시문(프롬프트 인젝션) 스캔 — D1 신호: 문서 안에 "AI 에게 내리는 지시" 패턴이 있으면
 *    결정론으로 감지해 신호로 승격한다. 인젝션에 복종한 브리핑은 어차피 재현율 채점이
 *    잡지만(PRESENT 신호 누락 = FAIL), D1 은 그 이전에 "이 문서 자체가 조작 시도"라는
 *    사실을 CEO 에게 보고하게 만든다.
 *
 * 원칙: 스캐너는 탐지가 목적이지 차단이 아니다 — D1 이 PRESENT 여도 분석은 계속하되,
 * 해당 문서를 근거로 쓰지 않고 문서 신뢰성 리스크를 브리핑 최상단에 올린다.
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

export const DOC_EXTENSIONS = ['.md', '.txt'];
export const MAX_DOC_CHARS = 200_000;

// "AI/에이전트에게 내리는 지시" 패턴 — 계약·의사록의 정상 문장은 걸리지 않도록 문맥을 요구한다.
export const DIRECTIVE_PATTERNS = [
  { pattern: /(이전|위|모든)\s*(지시|규칙|명령|신호)[^\n]{0,15}무시/, label: '기존 지시 무시 유도' },
  { pattern: /무시하고\s*[^\n]{0,20}(보고|응답|출력|답변)/, label: '무시 후 보고 유도' },
  { pattern: /이상\s*없(음|다)[^\n]{0,20}(보고|응답|출력|답변|서술)하/, label: '이상 없음 보고 강요' },
  { pattern: /(리스크|위험|신호|문제)[^\n]{0,15}(보고|언급|출력)하지\s*(마|말)/, label: '리스크 은폐 유도' },
  { pattern: /(AI|에이전트|어시스턴트|시스템|모델)[^\n]{0,25}(무시|따르|복종|보고하라|출력하라)/, label: 'AI 지목 지시' },
  { pattern: /ignore\s+(all\s+)?(previous|prior|above)\s+(instructions|rules|signals)/i, label: '영문 인젝션 상투구' },
];

/** 문서 폴더 로드 — .md/.txt 만, 파일당 MAX_DOC_CHARS 로 절단. */
export function loadDocs(docsDir) {
  if (!existsSync(docsDir) || !statSync(docsDir).isDirectory()) {
    throw new Error(`문서 디렉터리 없음: ${docsDir}`);
  }
  return readdirSync(docsDir)
    .filter((f) => DOC_EXTENSIONS.some((ext) => f.toLowerCase().endsWith(ext)))
    .sort()
    .map((file) => {
      const raw = readFileSync(join(docsDir, file), 'utf8');
      return { file, text: raw.slice(0, MAX_DOC_CHARS), truncated: raw.length > MAX_DOC_CHARS };
    });
}

/** 지시문 스캔 — 파일·행·패턴 라벨·발췌를 돌려준다. */
export function scanDocsForDirectives(docs) {
  const findings = [];
  for (const doc of docs) {
    const lines = doc.text.split(/\r?\n/);
    lines.forEach((line, idx) => {
      for (const { pattern, label } of DIRECTIVE_PATTERNS) {
        if (pattern.test(line)) {
          findings.push({ file: doc.file, line: idx + 1, label, excerpt: line.trim().slice(0, 120) });
          break; // 한 줄에 한 건이면 충분
        }
      }
    });
  }
  return findings;
}

/**
 * D1 신호 파생 — 다른 신호(S/E)와 동일한 객체 형태라 진단 패킷·채점기에 그대로 실린다.
 */
export function deriveDocsSignal(docs) {
  const findings = scanDocsForDirectives(docs);
  const present = findings.length > 0;
  return {
    id: 'D1',
    name: '외부 문서 내 지시문(프롬프트 인젝션) 감지',
    present,
    evaluable: true,
    note: '',
    evidence: {
      scannedFiles: docs.map((d) => d.file),
      findings,
    },
    markers: present ? [/지시문|인젝션|주입/, /문서\s*신뢰|외부\s*문서|조작/] : [],
    categoryPattern: /인젝션|지시문\s*감지|문서\s*신뢰성/,
    checkHints: ['문서 출처·작성자·전달 경로 확인', '원본 문서와 대조(변조 여부)', '해당 문서는 분석 근거에서 격리'],
  };
}
