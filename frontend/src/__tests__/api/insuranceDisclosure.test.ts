import { describe, it, expect, vi, beforeEach } from 'vitest';
import { insuranceDisclosureApi, disclosureErrorMessage } from '@/api/insuranceDisclosure';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

const pdfResponse = (sha = 'abc123', filename = 'disclosure-LIFE-TERM-20.pdf') => ({
  data: new Blob(['%PDF'], { type: 'application/pdf' }),
  headers: {
    'x-document-sha256': sha,
    'content-disposition': `attachment; filename="${filename}"`,
  },
});

const blobError = (body: string) => ({
  response: { status: 409, data: new Blob([body], { type: 'application/json' }) },
});

beforeEach(() => vi.clearAllMocks());

/**
 * 교부 API 계약.
 *
 * <p>핵심은 <b>오류 문구를 잃지 않는 것</b>이다. 두 엔드포인트가 PDF 를 주므로
 * {@code responseType: 'blob'} 을 쓰는데, 그러면 axios 는 <b>오류 본문도 Blob 으로</b> 준다.
 * 평소 쓰는 apiErrorMessage 는 그 안을 못 보고 기본 문구로 떨어져서, "완전판매 게이트 미통과"
 * 같은 조치에 필요한 사유가 통째로 사라진다.
 */
describe('insuranceDisclosureApi', () => {
  it('미리보기는 GET 이고 상품 코드를 URL 인코딩한다', async () => {
    mocked.get.mockResolvedValue(pdfResponse() as never);

    await insuranceDisclosureApi.preview('LIFE TERM/20');

    expect(mocked.get).toHaveBeenCalledWith(
      '/api/insurance/products/LIFE%20TERM%2F20/disclosure', { responseType: 'blob' });
  });

  it('응답 헤더에서 증빙 해시와 파일명을 꺼낸다', async () => {
    mocked.post.mockResolvedValue(pdfResponse('deadbeef', 'disclosure-AUTO-STD-01.pdf') as never);

    const result = await insuranceDisclosureApi.deliver({
      productCode: 'AUTO-STD-01', salesChannel: 'FC', contractorName: '홍길동',
    });

    // 이 해시가 곧 서버에 저장된 증빙이다 — 화면이 보여 줄 수 있어야 나중에 대조가 된다.
    expect(result.sha256).toBe('deadbeef');
    expect(result.fileName).toBe('disclosure-AUTO-STD-01.pdf');
  });

  it('교부 본문에 교부자를 싣지 않는다 — 서버가 JWT 에서만 파생한다', async () => {
    mocked.post.mockResolvedValue(pdfResponse() as never);

    await insuranceDisclosureApi.deliver({
      productCode: 'AUTO-STD-01', salesChannel: 'FC', contractorName: '홍길동',
    });

    const [, body] = mocked.post.mock.calls[0];
    expect(body).not.toHaveProperty('deliveredBy');
    expect(body).not.toHaveProperty('fcId');
  });
});

describe('disclosureErrorMessage — Blob 오류 본문', () => {
  it('Blob 안의 서버 사유를 되살린다 (error 키)', async () => {
    const err = blobError(JSON.stringify({ error: '완전판매 게이트: 교부 증빙이 없습니다' }));

    await expect(disclosureErrorMessage(err, '기본 문구'))
      .resolves.toBe('완전판매 게이트: 교부 증빙이 없습니다');
  });

  it('message 키도 받아 준다', async () => {
    const err = blobError(JSON.stringify({ message: '계약자가 청약과 다릅니다' }));

    await expect(disclosureErrorMessage(err, '기본 문구')).resolves.toBe('계약자가 청약과 다릅니다');
  });

  it('JSON 이 아니면 원문을 쓰지 않고 기본 문구로 떨어진다', async () => {
    // 게이트웨이가 HTML 오류 페이지를 주는 경우 — 그걸 그대로 띄우면 화면이 깨진다.
    const err = blobError('<html><body>502 Bad Gateway</body></html>');

    await expect(disclosureErrorMessage(err, '교부에 실패했습니다.')).resolves.toBe('교부에 실패했습니다.');
  });

  it('Blob 이 아닌 오류는 기본 문구다', async () => {
    await expect(disclosureErrorMessage(new Error('boom'), '기본 문구')).resolves.toBe('기본 문구');
  });
});
