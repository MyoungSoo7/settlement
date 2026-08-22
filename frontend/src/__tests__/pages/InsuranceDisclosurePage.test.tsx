import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import InsuranceDisclosurePage from '@/pages/system/InsuranceDisclosurePage';
import { insuranceDisclosureApi } from '@/api/insuranceDisclosure';
import { saveBlob } from '@/api/auditLog';

vi.mock('@/api/insuranceDisclosure', async (importOriginal) => {
  // disclosureErrorMessage 는 실제 구현을 쓴다 — Blob 오류 복원이 화면 문구에 닿는지 함께 본다.
  const actual = await importOriginal<typeof import('@/api/insuranceDisclosure')>();
  return { ...actual, insuranceDisclosureApi: { preview: vi.fn(), deliver: vi.fn() } };
});
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

const mocked = vi.mocked(insuranceDisclosureApi);
const mockedSave = vi.mocked(saveBlob);

const pdf = (sha = 'abc123') => ({
  blob: new Blob(['%PDF']), sha256: sha, fileName: 'disclosure-AUTO-STD-01.pdf',
});

const fillDeliver = (channel: 'FC' | 'BANCA' = 'FC') => {
  fireEvent.change(screen.getByLabelText('상품 코드'), { target: { value: 'AUTO-STD-01' } });
  fireEvent.change(screen.getByLabelText('계약자명'), { target: { value: '홍길동' } });
  fireEvent.change(screen.getByLabelText('판매 채널'), { target: { value: channel } });
};

const deliverButton = () => screen.getByRole('button', { name: '교부하고 증빙 기록' });

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('confirm', vi.fn(() => true));
});
afterEach(() => vi.unstubAllGlobals());

/**
 * 이 화면이 지키는 규율.
 *
 * <p>① <b>미리보기와 교부를 가른다.</b> 같은 PDF 를 주지만 미리보기는 흔적이 없고 교부는
 * 규제 증빙을 만들며 되돌릴 수 없다.
 * <p>② <b>교부자를 화면이 정하지 않는다.</b> 입력란 자체가 없어야 한다 — 있으면 그 자리가 위조 경로다.
 * <p>③ <b>BANCA 는 제휴은행이 필수</b>다(도메인 강제). 화면에서 막지 않으면 400 왕복이 된다.
 * <p>④ <b>증빙 해시를 보여 준다.</b> 나중에 문서 동일성을 다툴 때 대조 기준이 된다.
 */
describe('InsuranceDisclosurePage — 미리보기', () => {
  it('상품 코드가 비면 내려받을 수 없다', () => {
    render(<InsuranceDisclosurePage />);
    expect(screen.getByRole('button', { name: '미리보기 내려받기' })).toBeDisabled();
  });

  it('미리보기는 파일을 저장시키고 교부 API 는 부르지 않는다', async () => {
    mocked.preview.mockResolvedValue(pdf());
    render(<InsuranceDisclosurePage />);

    fireEvent.change(screen.getByLabelText('미리보기할 상품 코드'), { target: { value: 'AUTO-STD-01' } });
    fireEvent.click(screen.getByRole('button', { name: '미리보기 내려받기' }));

    await waitFor(() => expect(mockedSave).toHaveBeenCalled());
    // 미리보기는 증빙을 남기지 않는다 — 교부가 섞이면 그 구분이 무너진다.
    expect(mocked.deliver).not.toHaveBeenCalled();
    expect(screen.queryByTestId('deliver-result')).not.toBeInTheDocument();
  });
});

describe('InsuranceDisclosurePage — 교부', () => {
  it('교부자 입력란이 없다 — 서버가 JWT 에서만 파생한다', () => {
    render(<InsuranceDisclosurePage />);

    expect(screen.queryByLabelText(/교부자/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/FC/)).not.toBeInTheDocument();
  });

  it('상품·계약자가 차야 교부할 수 있다', () => {
    render(<InsuranceDisclosurePage />);
    expect(deliverButton()).toBeDisabled();

    fireEvent.change(screen.getByLabelText('상품 코드'), { target: { value: 'AUTO-STD-01' } });
    expect(deliverButton()).toBeDisabled();

    fireEvent.change(screen.getByLabelText('계약자명'), { target: { value: '홍길동' } });
    expect(deliverButton()).toBeEnabled();
  });

  it('BANCA 를 고르면 제휴은행 칸이 생기고, 비면 교부할 수 없다', () => {
    render(<InsuranceDisclosurePage />);

    // FC 일 때는 칸 자체가 없다 — 안 쓰는 칸을 남기면 무엇이 필수인지 흐려진다.
    expect(screen.queryByLabelText('제휴은행 코드')).not.toBeInTheDocument();

    fillDeliver('BANCA');

    expect(screen.getByLabelText('제휴은행 코드')).toBeInTheDocument();
    expect(deliverButton()).toBeDisabled();

    fireEvent.change(screen.getByLabelText('제휴은행 코드'), { target: { value: 'BANK-001' } });
    expect(deliverButton()).toBeEnabled();
  });

  it('FC 교부에는 제휴은행을 싣지 않는다', async () => {
    mocked.deliver.mockResolvedValue(pdf());
    render(<InsuranceDisclosurePage />);
    fillDeliver('FC');

    fireEvent.click(deliverButton());

    await waitFor(() => expect(mocked.deliver).toHaveBeenCalledWith({
      productCode: 'AUTO-STD-01', salesChannel: 'FC', contractorName: '홍길동',
    }));
  });

  it('청약 번호를 넣으면 함께 보낸다 — 서버가 그 청약과 대조한다', async () => {
    mocked.deliver.mockResolvedValue(pdf());
    render(<InsuranceDisclosurePage />);
    fillDeliver('FC');
    fireEvent.change(screen.getByLabelText('청약 번호'), { target: { value: 'APP-77' } });

    fireEvent.click(deliverButton());

    await waitFor(() => expect(mocked.deliver).toHaveBeenCalledWith(
      expect.objectContaining({ applicationId: 'APP-77' })));
  });

  it('확인을 취소하면 교부하지 않는다 — 되돌리는 경로가 없다', () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    render(<InsuranceDisclosurePage />);
    fillDeliver('FC');

    fireEvent.click(deliverButton());

    expect(mocked.deliver).not.toHaveBeenCalled();
  });

  it('교부 후 증빙 해시를 보여 준다', async () => {
    mocked.deliver.mockResolvedValue(pdf('deadbeef'));
    render(<InsuranceDisclosurePage />);
    fillDeliver('FC');

    fireEvent.click(deliverButton());

    await waitFor(() => expect(screen.getByTestId('deliver-sha256')).toHaveTextContent('deadbeef'));
    expect(mockedSave).toHaveBeenCalled();
  });

  it('Blob 으로 온 게이트 위반 사유가 화면에 그대로 뜬다', async () => {
    // responseType blob 이면 오류 본문도 Blob 이다. 여기서 문구를 잃으면 운영자는
    // "실패했습니다"만 보고 무엇을 고쳐야 하는지 알 수 없다.
    mocked.deliver.mockRejectedValue({
      response: { status: 409, data: new Blob([JSON.stringify({ error: '계약자가 청약과 다릅니다' })]) },
    });
    render(<InsuranceDisclosurePage />);
    fillDeliver('FC');

    fireEvent.click(deliverButton());

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('계약자가 청약과 다릅니다'));
    expect(screen.queryByTestId('deliver-result')).not.toBeInTheDocument();
  });
});
