import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.DATA_GO_KR_API_KEY = 'test-service-key';
const registry = await import('../../registry/client.mjs');

let lastRequest = {};
function stubFetch(responder) {
  globalThis.fetch = async (url, options = {}) => {
    lastRequest = { url: String(url), options };
    return responder(lastRequest);
  };
}
const jsonRes = (body, status = 200) => new Response(JSON.stringify(body), { status });

test('API_KEY/requireKey — env 키 인식', () => {
  assert.equal(registry.API_KEY, 'test-service-key');
  registry.requireKey();
});

test('normalizeBusinessNumber / validateBusinessNumber — 10자리와 체크섬 검증', () => {
  assert.equal(registry.normalizeBusinessNumber('124-81-00998'), '1248100998');
  assert.deepEqual(registry.validateBusinessNumber('124-81-00998'), {
    input: '124-81-00998',
    normalized: '1248100998',
    formatValid: true,
    checksumValid: true,
    valid: true,
  });
  assert.deepEqual(registry.validateBusinessNumber('123-45-67890'), {
    input: '123-45-67890',
    normalized: '1234567890',
    formatValid: true,
    checksumValid: false,
    valid: false,
  });
  assert.equal(registry.validateBusinessNumber('12-3').formatValid, false);
});

test('businessStatusCheck — status API POST body와 응답 반환', async () => {
  stubFetch(() => jsonRes({
    status_code: 'OK',
    data: [{ b_no: '1248100998', b_stt: '계속사업자', b_stt_cd: '01', tax_type: '부가가치세 일반과세자' }],
  }));
  const result = await registry.businessStatusCheck({ businessNumbers: ['124-81-00998'] });

  assert.match(lastRequest.url, /^https:\/\/api\.odcloud\.kr\/api\/nts-businessman\/v1\/status\?/);
  assert.match(lastRequest.url, /serviceKey=test-service-key/);
  assert.equal(lastRequest.options.method, 'POST');
  assert.equal(lastRequest.options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(lastRequest.options.body), { b_no: ['1248100998'] });
  assert.equal(result.data[0].b_stt_cd, '01');
});

test('businessAuthCheck — validate API POST body와 응답 반환', async () => {
  stubFetch(() => jsonRes({
    status_code: 'OK',
    data: [{ b_no: '1248100998', valid: '01', valid_msg: '확인되었습니다.' }],
  }));
  const result = await registry.businessAuthCheck({
    businesses: [{ b_no: '124-81-00998', start_dt: '19690113', p_nm: '홍길동', b_nm: '삼성전자' }],
  });

  assert.match(lastRequest.url, /\/validate\?/);
  assert.deepEqual(JSON.parse(lastRequest.options.body), {
    businesses: [{ b_no: '1248100998', start_dt: '19690113', p_nm: '홍길동', b_nm: '삼성전자' }],
  });
  assert.equal(result.data[0].valid, '01');
});

test('companyIdentityGate — 로컬 검증 후 상태조회와 선택적 진위확인 결합', async () => {
  let calls = 0;
  stubFetch(({ url }) => {
    calls += 1;
    if (url.includes('/status')) {
      return jsonRes({ data: [{ b_no: '1248100998', b_stt: '계속사업자', b_stt_cd: '01' }] });
    }
    return jsonRes({ data: [{ b_no: '1248100998', valid: '01', valid_msg: '확인되었습니다.' }] });
  });

  const result = await registry.companyIdentityGate({
    companyName: '삼성전자',
    businessNumber: '124-81-00998',
    representativeName: '홍길동',
    openingDate: '19690113',
    stockCode: '005930',
  });

  assert.equal(calls, 2);
  assert.equal(result.companyName, '삼성전자');
  assert.equal(result.businessNumber.valid, true);
  assert.equal(result.businessStatus.data[0].b_stt_cd, '01');
  assert.equal(result.businessAuth.data[0].valid, '01');
  assert.equal(result.analysisAllowed, true);
  assert.equal(result.identifiers.stockCode, '005930');
});

test('businessStatusCheck — API 오류 응답은 throw, 빈 번호는 거부', async () => {
  stubFetch(() => jsonRes({ error: 'bad key' }, 401));
  await assert.rejects(() => registry.businessStatusCheck({ businessNumbers: ['1248100998'] }), /HTTP 401/);
  await assert.rejects(() => registry.businessStatusCheck({ businessNumbers: [] }), /businessNumbers/);
});

test('postJson 재시도 — 503 이 2회 나와도 3번째 성공을 반환', async () => {
  process.env.NTS_RETRY_BASE_MS = '1';
  let calls = 0;
  stubFetch(() => {
    calls += 1;
    if (calls < 3) return jsonRes({ msg: 'API 서버 오류가 발생하였습니다.' }, 503);
    return jsonRes({ data: [{ b_no: '1248100998', b_stt_cd: '01' }] });
  });
  const result = await registry.businessStatusCheck({ businessNumbers: ['1248100998'] });
  assert.equal(calls, 3);
  assert.equal(result.data[0].b_stt_cd, '01');
});

test('postJson 재시도 — 지속 503 은 정확히 3회 시도 후 throw', async () => {
  process.env.NTS_RETRY_BASE_MS = '1';
  let calls = 0;
  stubFetch(() => {
    calls += 1;
    return jsonRes({ msg: 'API 서버 오류가 발생하였습니다.' }, 503);
  });
  await assert.rejects(() => registry.businessStatusCheck({ businessNumbers: ['1248100998'] }), /HTTP 503/);
  assert.equal(calls, 3);
});

test('postJson 재시도 — 4xx 는 재시도 없이 즉시 throw', async () => {
  process.env.NTS_RETRY_BASE_MS = '1';
  let calls = 0;
  stubFetch(() => {
    calls += 1;
    return jsonRes({ error: 'bad key' }, 401);
  });
  await assert.rejects(() => registry.businessStatusCheck({ businessNumbers: ['1248100998'] }), /HTTP 401/);
  assert.equal(calls, 1);
});
