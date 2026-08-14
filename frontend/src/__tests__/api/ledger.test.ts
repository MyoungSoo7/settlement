import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ledgerApi, type LedgerEntry } from '@/api/ledger';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const entry: LedgerEntry = {
  id: 1,
  referenceId: 55,
  referenceType: 'SETTLEMENT',
  entryType: 'SETTLEMENT_CONFIRMED',
  debitAccount: 'SELLER_PAYABLE',
  creditAccount: 'CASH',
  amount: '965000',
  status: 'POSTED',
  settlementDate: '2026-08-01',
  postedAt: '2026-08-01T09:00:00Z',
  memo: null,
  createdAt: '2026-08-01T09:00:00Z',
};

describe('ledgerApi 조회 (/api/ledger — ADMIN·MANAGER)', () => {
  beforeEach(() => vi.resetAllMocks());

  it('기간별 분개를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [entry] });

    const result = await ledgerApi.entries('2026-08-01', '2026-08-31');

    expect(api.get).toHaveBeenCalledWith('/api/ledger/entries', {
      params: { from: '2026-08-01', to: '2026-08-31' },
    });
    expect(result[0].status).toBe('POSTED');
  });

  it('정산 1건의 분개를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [entry] });

    const result = await ledgerApi.bySettlement(55);

    expect(api.get).toHaveBeenCalledWith('/api/ledger/settlements/55');
    expect(result[0].referenceId).toBe(55);
  });

  it('환불 1건의 역분개를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [{ ...entry, id: 2, referenceType: 'REFUND', status: 'REVERSED' }],
    });

    const result = await ledgerApi.byRefund(9);

    expect(api.get).toHaveBeenCalledWith('/api/ledger/refunds/9');
    expect(result[0].status).toBe('REVERSED');
  });
});

describe('ledgerApi 기간·시산표·마감 (/admin — ADMIN 전용)', () => {
  beforeEach(() => vi.resetAllMocks());

  it('기간 상태를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        id: 1,
        periodYm: '2026-08',
        status: 'OPEN',
        closedAt: null,
        closedBy: null,
        totalDebit: '0',
        totalCredit: '0',
        createdAt: '2026-08-01T00:00:00Z',
      },
    });

    const result = await ledgerApi.period('2026-08');

    expect(api.get).toHaveBeenCalledWith('/admin/ledger-periods/2026-08');
    expect(result.status).toBe('OPEN');
  });

  it('시산표는 차·대 균형 플래그를 그대로 전달한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        periodYm: '2026-08',
        lines: [{ account: 'CASH', debit: '100', credit: '0', net: '100' }],
        totalDebit: '100',
        totalCredit: '100',
        balanced: true,
      },
    });

    const result = await ledgerApi.trialBalance('2026-08');

    expect(api.get).toHaveBeenCalledWith('/admin/ledger-periods/2026-08/trial-balance');
    expect(result.balanced).toBe(true);
  });

  it('불균형 시산표도 판정을 왜곡하지 않고 그대로 노출한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        periodYm: '2026-07',
        lines: [],
        totalDebit: '100',
        totalCredit: '90',
        balanced: false,
      },
    });

    const result = await ledgerApi.trialBalance('2026-07');

    expect(result.balanced).toBe(false);
  });

  it('기간을 마감한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        id: 1,
        periodYm: '2026-07',
        status: 'CLOSED',
        closedAt: '2026-08-01T00:00:00Z',
        closedBy: 'admin',
        totalDebit: '100',
        totalCredit: '100',
        createdAt: '2026-07-01T00:00:00Z',
      },
    });

    const result = await ledgerApi.close('2026-07');

    expect(api.post).toHaveBeenCalledWith('/admin/ledger-periods/2026-07/close');
    expect(result.status).toBe('CLOSED');
  });

  it('이미 마감된 기간 재마감은 오류가 전파된다', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({ response: { status: 409 } });

    await expect(ledgerApi.close('2026-07')).rejects.toMatchObject({ response: { status: 409 } });
  });
});
