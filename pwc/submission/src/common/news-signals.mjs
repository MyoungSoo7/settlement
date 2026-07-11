const CATEGORY_DEFS = [
  {
    id: 'reputation',
    name: '기업평판/브랜드 이미지',
    keywords: ['평판', '이미지', '브랜드', '논란', '불매', '소비자', '고객 불만', '품질', '리콜', '갑질', '민원'],
    crossCheck: '매출 성장률, 반품/클레임, 고객 이탈, 채널별 매출 변화를 확인',
    advice: '브랜드 훼손 신호는 단기 매출보다 채널별 주문 취소, 반품률, 주요 거래처 반응으로 먼저 검증한다.',
  },
  {
    id: 'industry',
    name: '관심산업/시장 환경',
    keywords: ['업계', '산업', '시장', '트렌드', '수요', '소비동향', '온라인', '플랫폼', '패션', '섬유', '의류'],
    crossCheck: '재고 증가율, 매출 성장률, 사업부별 성장률, ECOS 물가/금리 환경을 확인',
    advice: '산업 변화 신호는 재고 회전과 제품군별 매출 믹스 변화로 연결해 확인한다.',
  },
  {
    id: 'investment',
    name: '투자동향/자금소요',
    keywords: ['투자', '증설', '설비', '물류', '자동화', '인수', 'M&A', '유상증자', '자금조달', 'CAPEX'],
    crossCheck: '차입금, 이자보상배율, 투자 현금흐름, 유동비율을 확인',
    advice: '투자 확대 신호는 성장 기회와 동시에 현금 소요이므로 차입 만기와 투자 회수 기간을 같이 본다.',
  },
  {
    id: 'business',
    name: '사업동향/제휴/확장',
    keywords: ['진출', '제휴', '계약', '수주', '공급', '파트너', '신사업', '출점', '매장', '해외', '브랜드 론칭'],
    crossCheck: '수주/계약 공시, 매출채권 증가, 고객 집중도, 계약부채 변화를 확인',
    advice: '사업 확장 신호는 매출 인식 속도와 현금 회수 속도가 같이 움직이는지 확인한다.',
  },
  {
    id: 'finance',
    name: '재무동향/실적 압박',
    keywords: ['실적', '매출', '영업이익', '적자', '흑자', '감소', '증가', '부채', '차입', '이자', '유동성', '재고'],
    crossCheck: 'E1~E4 재무 신호, 현금흐름, 차입금, 재고자산 평가손실을 확인',
    advice: '재무 뉴스는 수치가 과장될 수 있으므로 DART 재무제표와 분기 추세로 재계산한다.',
  },
  {
    id: 'risk',
    name: '규제/소송/보안/운영 리스크',
    keywords: ['규제', '제재', '소송', '고발', '조사', '감사', '보안', '장애', '유출', '구조조정', '폐점', '파업'],
    crossCheck: '공시 정정/해명, 충당부채, 법무 비용, 운영 중단 영향을 확인',
    advice: '규제와 운영 리스크는 손익 영향보다 발생 가능성과 대응 책임자를 먼저 확정한다.',
  },
];

function textOf(item) {
  return `${item.title ?? ''} ${item.description ?? ''}`.toLowerCase();
}

function uniqByUrl(items) {
  const seen = new Set();
  const out = [];
  for (const item of items) {
    const key = item.url || item.naverUrl || `${item.title}|${item.pubDate}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

function compactItem(item) {
  return {
    title: item.title ?? '',
    description: item.description ?? '',
    pubDate: item.pubDate ?? '',
    url: item.url ?? item.naverUrl ?? '',
    naverUrl: item.naverUrl ?? item.url ?? '',
  };
}

export function classifyNewsSignals({ company, searches = [], maxExamplesPerCategory = 5 } = {}) {
  const items = uniqByUrl(searches.flatMap((s) => s.items ?? []));
  const categories = {};
  const advice = [];

  for (const def of CATEGORY_DEFS) {
    const matches = items.filter((item) => def.keywords.some((kw) => textOf(item).includes(kw.toLowerCase())));
    categories[def.id] = {
      id: def.id,
      name: def.name,
      count: matches.length,
      keywords: def.keywords,
      examples: matches.slice(0, maxExamplesPerCategory).map(compactItem),
      crossCheck: def.crossCheck,
    };
    if (matches.length > 0) {
      advice.push({
        category: def.id,
        title: def.name,
        signal: `${matches.length}건의 관련 뉴스 메타데이터 감지`,
        crossCheck: def.crossCheck,
        suggestedAction: def.advice,
      });
    }
  }

  return {
    enabled: true,
    company: String(company ?? '').trim(),
    searches: searches.map((s) => ({
      query: s.query,
      total: s.total,
      display: s.display,
      sort: s.sort,
      itemCount: s.items?.length ?? 0,
    })),
    totalUniqueItems: items.length,
    categories,
    advice,
    caveat: '뉴스는 제목, 요약, 발행일, 링크 메타데이터만 사용한 보조 신호이며 확정 사실로 취급하지 않습니다.',
  };
}

export function newsDisabled(reason) {
  return {
    enabled: false,
    reason,
    categories: Object.fromEntries(CATEGORY_DEFS.map((d) => [d.id, {
      id: d.id,
      name: d.name,
      count: 0,
      keywords: d.keywords,
      examples: [],
      crossCheck: d.crossCheck,
    }])),
    advice: [],
    caveat: '뉴스 신호가 생성되지 않았습니다.',
  };
}

export function buildNewsSignalSummary({ newsSignals, externalSignals = [] } = {}) {
  if (!newsSignals?.enabled) return `뉴스 신호 미생성: ${newsSignals?.reason ?? '실행 안 함'}`;
  const active = Object.values(newsSignals.categories ?? {}).filter((c) => c.count > 0);
  const presentExternal = externalSignals.filter((s) => s.present).map((s) => s.id).join(', ') || '없음';
  const evaluatedExternal = externalSignals.map((s) => s.id).filter(Boolean).join(', ') || '없음';
  if (active.length === 0) {
    return `뉴스는 확정 사실이 아니라 보조 신호입니다. 이번 검색에서는 분류 기준에 걸린 뉴스 테마가 없으며, DART PRESENT 신호는 ${presentExternal}입니다. 평가된 DART 신호는 ${evaluatedExternal}입니다.`;
  }
  const themes = active.map((c) => `${c.name} ${c.count}건`).join(', ');
  return `뉴스는 확정 사실이 아니라 보조 신호입니다. 감지 테마: ${themes}. DART PRESENT 신호는 ${presentExternal}이며, 평가된 DART 신호는 ${evaluatedExternal}입니다. 뉴스 테마는 각 카테고리의 crossCheck 항목으로 재무/공시 데이터와 교차 확인해야 합니다.`;
}
