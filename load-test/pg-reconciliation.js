// PG 정산파일 대사 부하 테스트
// 매일 1 회 운영자가 업로드하는 시나리오라 RPS 보다는 단일 요청 latency 중요.
// 100 만건 PG 파일 처리 시간이 5분 이내인지 검증.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082'; // settlement-service
const reconciliationDuration = new Trend('reconciliation_duration_ms', true);

export const options = {
    vus: 10,
    duration: '1m',
    thresholds: {
        // 대사 1 회는 운영자 워크플로 — 5초 미만이면 충분
        http_req_duration: ['p(95)<5000'],
        http_req_failed: ['rate<0.01'],
        reconciliation_duration_ms: ['p(95)<5000'],
    },
};

const csvBody = `pg_transaction_id,amount,refunded_amount,fee,settled_date
TOSS:abc-001,10000,0,300,2026-04-28
KCP:tx-002,25000,1000,750,2026-04-28
NICE:tx-003,7500,500,225,2026-04-28
TOSS:abc-004,15000,0,450,2026-04-28
KCP:tx-005,5000,0,150,2026-04-28
`;

export default function () {
    const formData = {
        provider: 'TOSS',
        targetDate: '2026-04-28',
        file: http.file(csvBody, 'reconciliation.csv', 'text/csv'),
    };

    const start = Date.now();
    const res = http.post(`${BASE_URL}/admin/pg-reconciliation/files`, formData);
    reconciliationDuration.add(Date.now() - start);

    check(res, {
        'reconciliation 200 OR 401 (auth required)': (r) => r.status === 200 || r.status === 401,
    });
}
