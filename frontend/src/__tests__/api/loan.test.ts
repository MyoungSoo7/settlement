import { describe, it, expect, vi, beforeEach } from 'vitest';
import { loanApi, type LoanResponse, type CorporateLoan } from '@/api/loan';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const sellerLoan: LoanResponse = {
  id: 1,
  sellerId: 7,
  principal: 800_000,
  fee: 800,
  outstanding: 800_800,
  status: 'DISBURSED',
  disbursedAt: '2026-07-24T09:00:00',
  dueAt: '2026-07-31T09:00:00',
};

const corporateLoan: CorporateLoan = {
  id: 5001,
  stockCode: '005930',
  corpName: '삼성전자',
  principal: 1_000_000,
  fee: 6_000,
  outstanding: 1_006_000,
  termDays: 30,
  creditScore: 82,
  creditGrade: 'A',
  status: 'DISBURSED',
};

describe('loanApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('선정산 대출', () => {
    it('신청은 POST /loans 로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: sellerLoan });

      const result = await loanApi.request({ sellerId: 7, principal: 800_000, financingDays: 7 });

      expect(api.post).toHaveBeenCalledWith('/loans', { sellerId: 7, principal: 800_000, financingDays: 7 });
      expect(result.id).toBe(1);
    });

    it('실행은 POST /loans/{id}/disburse 로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: sellerLoan });

      await loanApi.disburse(1);

      expect(api.post).toHaveBeenCalledWith('/loans/1/disburse');
    });

    it('셀러별 목록은 GET /loans?sellerId= 로 조회하고 만기일을 포함한다', async () => {
      vi.mocked(api.get).mockResolvedValueOnce({ data: [sellerLoan] });

      const result = await loanApi.bySeller(7);

      expect(api.get).toHaveBeenCalledWith('/loans?sellerId=7');
      expect(result[0].dueAt).toBe('2026-07-31T09:00:00');
    });

    it('연체 처리는 POST /loans/{id}/overdue 로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: { ...sellerLoan, status: 'OVERDUE' } });

      const result = await loanApi.markOverdue(1);

      expect(api.post).toHaveBeenCalledWith('/loans/1/overdue');
      expect(result.status).toBe('OVERDUE');
    });

    it('상각 처리는 POST /loans/{id}/write-off 로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: { ...sellerLoan, status: 'WRITTEN_OFF' } });

      const result = await loanApi.writeOff(1);

      expect(api.post).toHaveBeenCalledWith('/loans/1/write-off');
      expect(result.status).toBe('WRITTEN_OFF');
    });
  });

  describe('기업대출', () => {
    it('상환은 POST /loans/corporate/{id}/repay 로 금액을 함께 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: { ...corporateLoan, outstanding: 406_000 } });

      const result = await loanApi.repayCorporate(5001, 600_000);

      expect(api.post).toHaveBeenCalledWith('/loans/corporate/5001/repay', { amount: 600_000 });
      expect(result.outstanding).toBe(406_000);
    });

    it('실행은 POST /loans/corporate/{id}/disburse 로 보낸다', async () => {
      vi.mocked(api.post).mockResolvedValueOnce({ data: corporateLoan });

      await loanApi.disburseCorporate(5001);

      expect(api.post).toHaveBeenCalledWith('/loans/corporate/5001/disburse');
    });

    it('API 오류 시 에러를 throw한다', async () => {
      const error = { response: { status: 403 } };
      vi.mocked(api.post).mockRejectedValueOnce(error);

      await expect(loanApi.repayCorporate(9999, 1)).rejects.toMatchObject({ response: { status: 403 } });
    });
  });
});
