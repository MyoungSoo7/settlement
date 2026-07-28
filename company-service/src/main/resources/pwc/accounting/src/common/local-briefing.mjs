const ko = {
  missing: '\uBBF8\uC81C\uACF5',
  titleSuffix: 'CEO \uB9AC\uC2A4\uD06C \uBE0C\uB9AC\uD551',
  date: '\uC791\uC131\uC77C',
  company: '\uD68C\uC0AC\uBA85',
  corpCode: 'DART \uACE0\uC720\uBC88\uD638',
  stockCode: '\uC885\uBAA9\uCF54\uB4DC',
  year: '\uAE30\uC900 \uC5F0\uB3C4',
  fsDiv: '\uC7AC\uBB34\uC81C\uD45C \uAD6C\uBD84',
  summary: '\uC694\uC57D',
  conclusion: '\uACB0\uB860',
  evidence: '\uADFC\uAC70',
  why: '\uC65C \uBB38\uC81C\uC778\uAC00',
  confidence: '\uD655\uC2E0\uB3C4',
  test: '\uD310\uBCC4 \uD14C\uC2A4\uD2B8',
  action: '\uAD8C\uACE0 \uC870\uCE58',
  scope: '\uD655\uC778 \uBC94\uC704\uC640 \uD55C\uACC4',
  news: '\uBCF4\uC870 \uC2E0\uD638 - \uB274\uC2A4 \uBA54\uD0C0\uB370\uC774\uD130',
  checkNeeded: '\uD655\uC778 \uD544\uC694',
  sourceCheck: '\uC6D0\uCC9C \uACF5\uC2DC\uC640 \uC8FC\uC11D\uC744 \uC7AC\uB300\uC870',
  disclaimer: '\uBCF8 \uC790\uB8CC\uB294 CEO \uACBD\uC601 \uB9AC\uC2A4\uD06C \uBD84\uC11D \uBCF4\uC870 \uC790\uB8CC\uC774\uBA70 \uD22C\uC790\uC790\uBB38 \uB610\uB294 \uD22C\uC790\uAD8C\uC720\uAC00 \uC544\uB2D9\uB2C8\uB2E4.',
};

function fmt(value) {
  if (value === null || value === undefined || value === '') return ko.missing;
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)));
  if (Array.isArray(value)) return value.length ? value.join(', ') : ko.missing;
  return String(value);
}

function evidenceLines(evidence = {}) {
  return Object.entries(evidence)
    .filter(([, value]) => value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0))
    .slice(0, 8)
    .map(([key, value]) => `- ${key}: ${fmt(value)}`);
}

function hintLines(signal) {
  const hints = Array.isArray(signal.checkHints) ? signal.checkHints.filter(Boolean) : [];
  return hints.length ? hints.slice(0, 4).map((hint) => `- ${hint}`) : [`- ${ko.sourceCheck}`];
}

function signalProblem(signal) {
  if (signal.id === 'E1') {
    return '\uB9E4\uCD9C \uC99D\uAC00\uBCF4\uB2E4 \uB9E4\uCD9C\uCC44\uAD8C \uC99D\uAC00\uAC00 \uBE60\uB974\uAC70\uB098 \uC601\uC5C5\uD604\uAE08\uD750\uB984 \uC804\uD658\uC774 \uC57D\uD558\uBA74, \uD68C\uACC4\uC0C1 \uC774\uC775\uC774 \uD604\uAE08\uC73C\uB85C \uD68C\uC218\uB418\uB294 \uC18D\uB3C4\uC640 \uC870\uAC74\uC744 \uBCC4\uB3C4\uB85C \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4.';
  }
  if (signal.id === 'E5') {
    return '\uC815\uC815\u00B7\uD574\uBA85 \uACF5\uC2DC\uAC00 \uBC18\uBCF5\uB418\uBA74 \uC22B\uC790 \uC790\uCCB4\uBCF4\uB2E4 \uB300\uC678 \uCEE4\uBBA4\uB2C8\uCF00\uC774\uC158\uACFC \uACF5\uC2DC \uAC80\uC218 \uCCB4\uC778\uC758 \uC2E0\uB8B0 \uBB38\uC81C\uAC00 \uBA3C\uC800 \uBD80\uAC01\uB420 \uC218 \uC788\uC2B5\uB2C8\uB2E4.';
  }
  return '\uD574\uB2F9 \uC2E0\uD638\uB294 \uD655\uC815 \uC0AC\uC2E4\uC774 \uC544\uB2C8\uB77C \uACBD\uC601\uC9C4\uC774 \uC6D0\uCC9C\uC790\uB8CC\uB85C \uC7AC\uD655\uC778\uD574\uC57C \uD560 \uAD00\uCC30\uAC12\uC785\uB2C8\uB2E4.';
}

function confidence(signal) {
  return signal.id === 'E5'
    ? '\uAC00\uB2A5\uC131 \uB192\uC74C. \uACF5\uC2DC \uBA54\uD0C0\uB370\uC774\uD130\uB85C \uAC74\uC218\uB294 \uD655\uC778\uB410\uC9C0\uB9CC, \uC815\uC815 \uB0B4\uC6A9\uC758 \uC911\uC694\uB3C4\uB294 \uC6D0\uBB38 \uB300\uC870\uAC00 \uD544\uC694\uD569\uB2C8\uB2E4.'
    : '\uD655\uC778 \uD544\uC694. \uC9C4\uB2E8 \uD328\uD0B7\uC758 \uC218\uCE58 \uC2E0\uD638\uB294 \uAE30\uC900\uC744 \uB118\uC5C8\uC9C0\uB9CC, \uC6D0\uCC9C \uC8FC\uC11D\uACFC \uAE30\uAC04\uBCC4 \uC138\uBD80 \uB0B4\uC5ED\uC73C\uB85C \uC7AC\uAC80\uC99D\uD574\uC57C \uD569\uB2C8\uB2E4.';
}

function presentSignalSection(signal, index) {
  return `## ${index}. ${signal.name}

**${ko.conclusion}.**
${signal.name} \uC2E0\uD638\uAC00 PRESENT\uB85C \uAC10\uC9C0\uB410\uC2B5\uB2C8\uB2E4. \uC774 \uD56D\uBAA9\uC740 \uD655\uC815 \uD310\uB2E8\uC774 \uC544\uB2C8\uB77C CEO\uAC00 \uBA3C\uC800 \uD655\uC778\uD560 \uACBD\uC601 \uB9AC\uC2A4\uD06C \uD6C4\uBCF4\uC785\uB2C8\uB2E4.

**${ko.evidence}.**
${evidenceLines(signal.evidence).join('\n')}

**${ko.why}.**
${signalProblem(signal)}

**${ko.confidence}.**
${confidence(signal)}

**${ko.test}.**
- \uC6D0\uCC9C \uACF5\uC2DC\u00B7\uC8FC\uC11D\u00B7\uAE30\uAC04\uBCC4 \uB370\uC774\uD130\uB97C \uB300\uC870\uD574 \uC2E0\uD638\uAC00 \uB2E8\uC21C \uBD84\uB958 \uBB38\uC81C\uC778\uC9C0 \uC2E4\uC9C8 \uBCC0\uD654\uC778\uC9C0 \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4.
- \uAC19\uC740 \uC218\uCE58\uAC00 \uB2E4\uC74C \uBD84\uAE30\uC5D0\uB3C4 \uBC18\uBCF5\uB418\uB294\uC9C0 \uD655\uC778\uD574 \uC77C\uD68C\uC131 \uC694\uC778\uACFC \uAD6C\uC870\uC801 \uC694\uC778\uC744 \uBD84\uB9AC\uD574\uC57C \uD569\uB2C8\uB2E4.

**${ko.action}.**
${hintLines(signal).join('\n')}
`;
}

function cleanSection(packet) {
  const evaluated = (packet.signals ?? []).length;
  return `## ${ko.scope}

**${ko.conclusion}.**
\uC9C4\uB2E8 \uD328\uD0B7 \uAE30\uC900\uC73C\uB85C \uC720\uC758\uBBF8\uD55C \uC774\uC0C1 \uC2E0\uD638\uAC00 \uD655\uC778\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uBC1C\uD654(PRESENT)\uD55C \uC678\uBD80 \uB9AC\uC2A4\uD06C \uC2E0\uD638\uB294 \uC5C6\uC2B5\uB2C8\uB2E4.

**${ko.evidence}.**
- \uD3C9\uAC00 \uB300\uC0C1 \uC2E0\uD638 \uC218: ${evaluated}
- DART/ECOS \uBC0F \uC81C\uACF5\uB41C \uB0B4\uBD80 CSV \uBC94\uC704\uC5D0\uC11C \uC784\uACC4\uAC12\uC744 \uB118\uC740 \uD56D\uBAA9\uB9CC \uB9AC\uC2A4\uD06C\uB85C \uBD84\uB958\uD588\uC2B5\uB2C8\uB2E4.

**${ko.confidence}.**
${ko.checkNeeded}. \uBBF8\uBC1C\uD654\uB294 \uD604\uC7AC \uC785\uB825 \uBC94\uC704\uC5D0\uC11C\uC758 \uAD00\uCC30\uAC12\uC774\uBA70, \uC6D0\uCC9C \uC790\uB8CC\uAC00 \uCD94\uAC00\uB418\uBA74 \uD310\uB2E8\uC774 \uBC14\uB00C \uC218 \uC788\uC2B5\uB2C8\uB2E4.

**${ko.action}.**
- \uB2E4\uC74C \uBD84\uAE30 \uACF5\uC2DC\uC640 \uB0B4\uBD80 CSV\uB97C \uAC19\uC740 \uAE30\uC900\uC73C\uB85C \uC7AC\uC2E4\uD589\uD558\uC2ED\uC2DC\uC624.
- \uB0B4\uBD80 \uC6D0\uC7A5\u00B7\uCC44\uAD8C aging\u00B7\uC6D0\uAC00 \uBC30\uBD80 \uB370\uC774\uD130\uAC00 \uC788\uC73C\uBA74 \uBCC4\uB3C4 \uD45C\uC900 CSV\uB85C \uBCF4\uAC15\uD558\uC2ED\uC2DC\uC624.
`;
}

function newsSection(newsSignals) {
  if (!newsSignals) return '';
  const categories = Array.isArray(newsSignals.categories) ? newsSignals.categories : [];
  if (!categories.length) return '';
  const rows = categories
    .slice(0, 6)
    .map((c) => `| ${fmt(c.label ?? c.name)} | ${fmt(c.count)} | ${fmt(c.advice ?? c.checkPoint ?? ko.checkNeeded)} |`)
    .join('\n');
  return `## ${ko.news}

\uB274\uC2A4\uB294 \uC81C\uBAA9\u00B7\uC694\uC57D\u00B7\uBC1C\uD589\uC77C\u00B7\uB9C1\uD06C \uBA54\uD0C0\uB370\uC774\uD130\uB9CC \uC0AC\uC6A9\uD55C \uBCF4\uC870 \uC2E0\uD638\uC774\uBA70 \uD655\uC815 \uC0AC\uC2E4\uB85C \uCDE8\uAE09\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.

| \uD14C\uB9C8 | \uAC74\uC218 | \uAD50\uCC28 \uD655\uC778 \uD3EC\uC778\uD2B8 |
|---|---:|---|
${rows}
`;
}

export function renderLocalBriefing(packet, opts = {}) {
  const company = opts.companyName ?? packet?.corp?.name ?? '\uBD84\uC11D \uB300\uC0C1 \uAE30\uC5C5';
  const corp = packet?.corp ?? {};
  const present = (packet?.signals ?? []).filter((signal) => signal.present);
  const date = opts.date ?? new Date().toISOString().slice(0, 10);
  const signalSections = present.length
    ? present.map((signal, index) => presentSignalSection(signal, index + 1)).join('\n')
    : cleanSection(packet ?? {});

  return `# ${company} ${ko.titleSuffix}

- ${ko.date}: ${date}
- ${ko.company}: ${fmt(corp.name ?? company)}
- ${ko.corpCode}: ${fmt(corp.corpCode)}
- ${ko.stockCode}: ${fmt(corp.stockCode)}
- ${ko.year}: ${fmt(packet?.year)}
- ${ko.fsDiv}: ${fmt(packet?.fsDiv)}

## ${ko.summary}

\uC9C4\uB2E8 \uD328\uD0B7\uC5D0\uC11C PRESENT\uB85C \uAC10\uC9C0\uB41C \uC2E0\uD638\uB294 ${present.length}\uAC74\uC785\uB2C8\uB2E4. \uBCF8 \uBE0C\uB9AC\uD551\uC740 \uAC10\uC9C0\uB41C \uC2E0\uD638\uB9CC \uB9AC\uC2A4\uD06C \uD6C4\uBCF4\uB85C \uB2E4\uB8E8\uBA70, \uBBF8\uBC1C\uD654 \uC2E0\uD638\uB294 \uD655\uC778 \uBC94\uC704\uC640 \uD55C\uACC4\uC5D0\uC11C\uB9CC \uC124\uBA85\uD569\uB2C8\uB2E4.

${signalSections}
${newsSection(packet?.newsSignals)}
## ${ko.scope}

- \uC774 \uBB38\uC11C\uB294 \`diagnostic-packet.json\`\uC758 DART/ECOS/\uB274\uC2A4/\uB0B4\uBD80 CSV \uBA54\uD0C0\uB370\uC774\uD130\uB97C \uAE30\uC900\uC73C\uB85C \uC791\uC131\uD588\uC2B5\uB2C8\uB2E4.
- \uB274\uC2A4\uB294 \uBCF4\uC870 \uC2E0\uD638\uC774\uBA70 \uD655\uC815 \uC0AC\uC2E4\uB85C \uCDE8\uAE09\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.
- \uB300\uD45C\uC790\uBA85\uACFC \uAC1C\uC5C5\uC77C\uC790\uAC00 \uC81C\uACF5\uB418\uC9C0 \uC54A\uC740 \uACBD\uC6B0 \uAD6D\uC138\uCCAD \uC9C4\uC704\uD655\uC778\uC740 \uC0DD\uB7B5\uB418\uACE0 \uC0C1\uD0DC\uC870\uD68C\uB9CC \uBC18\uC601\uB429\uB2C8\uB2E4.

${ko.disclaimer}
`;
}
