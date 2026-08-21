import React, { useState } from 'react';
import {
  insuranceDisclosureApi,
  disclosureErrorMessage,
  type DeliverDisclosureInput,
  type RenderedDisclosure,
} from '@/api/insuranceDisclosure';
import { saveBlob } from '@/api/auditLog';
import { useToast } from '@/contexts/useToast';

/**
 * 보험 상품설명서 — 미리보기와 교부.
 *
 * <p><b>왜 화면이 필요한가.</b> 청약 승인은 완전판매 게이트를 통과해야 한다 — 교부 증빙이 없으면
 * 서버가 409 로 거절한다. 교부는 API 로만 가능했으므로, 화면이 없는 동안 승인은 UI 경로로
 * 통과시킬 방법이 없었다.
 *
 * <p><b>미리보기와 교부를 뚜렷하게 가른다.</b> 둘 다 같은 PDF 를 내려주지만 성격이 정반대다 —
 * 미리보기는 흔적이 없고, 교부는 <b>규제 증빙을 만들며 되돌리는 경로가 없다</b>. 그래서 교부는
 * 별도 구획에 두고 확인을 받는다. 한 버튼 옆에 나란히 두면 오조작이 조용해진다.
 *
 * <p><b>교부자 입력란은 없다.</b> 서버가 JWT 주체에서만 파생한다 — 증빙 문서의 "누가 교부했는가"를
 * 화면이 받으면 그 자리가 곧 위조 경로가 된다.
 */

/**
 * 시드된 상품·제휴은행 코드.
 *
 * <p>서버에 <b>카탈로그 조회 API 가 없다</b>(상품을 넣는 경로도 마이그레이션 시드뿐이다).
 * 그래서 이 목록은 정본이 아니라 <b>입력 편의</b>이고, 목록에 없는 코드도 그대로 보낼 수 있다.
 * 카탈로그 API 가 생기면 이 상수는 지우고 서버에서 받아야 한다.
 */
const PRODUCT_CODES = ['LIFE-TERM-20', 'LIFE-WHOLE-01', 'HEALTH-CI-01', 'AUTO-STD-01', 'FIRE-HOME-01'];
const BANK_CODES = ['BANK-001', 'BANK-002', 'BANK-003'];

const inputClass = 'mt-1 w-full rounded border px-3 py-2';
const buttonClass = 'rounded px-4 py-2 text-sm font-semibold disabled:opacity-50';

/**
 * 힌트를 {@code <label>} <b>밖에</b> 둔다. 안에 넣으면 힌트 문장까지 입력칸의 접근성 이름이 돼
 * ("청약 번호 넣으면 소유·상품·채널·계약자가…") 스크린리더가 칸마다 한 문단을 읽는다.
 * 그걸 aria-label 로 덮으면 이번엔 보이는 글자와 읽히는 이름이 어긋난다(WCAG Label in Name).
 */
const Field: React.FC<{ label: string; hint?: string; children: React.ReactNode }> =
  ({ label, hint, children }) => (
    <div className="text-sm">
      <label className="block">
        <span className="text-gray-600">{label}</span>
        {children}
      </label>
      {hint && <span className="mt-1 block text-xs text-gray-500">{hint}</span>}
    </div>
  );

const InsuranceDisclosurePage: React.FC = () => {
  const { showToast } = useToast();

  const [previewCode, setPreviewCode] = useState('');
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const [productCode, setProductCode] = useState('');
  const [salesChannel, setSalesChannel] = useState<'FC' | 'BANCA'>('FC');
  const [partnerBankCode, setPartnerBankCode] = useState('');
  const [contractorName, setContractorName] = useState('');
  const [applicationId, setApplicationId] = useState('');
  const [delivering, setDelivering] = useState(false);
  const [deliverError, setDeliverError] = useState<string | null>(null);
  const [delivered, setDelivered] = useState<RenderedDisclosure | null>(null);

  const preview = async () => {
    setPreviewError(null);
    setPreviewing(true);
    try {
      const result = await insuranceDisclosureApi.preview(previewCode.trim());
      saveBlob(result.blob, result.fileName);
    } catch (err) {
      setPreviewError(await disclosureErrorMessage(err, '상품설명서를 불러오지 못했습니다.'));
    } finally {
      setPreviewing(false);
    }
  };

  // BANCA 는 제휴은행이 필수다(도메인이 강제) — 화면에서 막지 않으면 400 을 받고 돌아온다.
  const bankRequired = salesChannel === 'BANCA';
  const deliverReady = productCode.trim() !== '' && contractorName.trim() !== ''
    && (!bankRequired || partnerBankCode.trim() !== '');

  const deliver = () => {
    if (!window.confirm(
      '상품설명서를 교부하고 완전판매 증빙을 기록합니다.\n\n'
      + `상품 ${productCode.trim()} · 계약자 ${contractorName.trim()}`
      + `${applicationId.trim() ? ` · 청약 ${applicationId.trim()}` : ' · 청약 미지정'}\n\n`
      + '증빙은 되돌리는 경로가 없습니다. 계속하시겠습니까?')) return;

    void (async () => {
      setDeliverError(null);
      setDelivered(null);
      setDelivering(true);
      try {
        const input: DeliverDisclosureInput = {
          productCode: productCode.trim(),
          salesChannel,
          contractorName: contractorName.trim(),
          ...(applicationId.trim() ? { applicationId: applicationId.trim() } : {}),
          ...(bankRequired ? { partnerBankCode: partnerBankCode.trim() } : {}),
        };
        const result = await insuranceDisclosureApi.deliver(input);
        setDelivered(result);
        saveBlob(result.blob, result.fileName);
        showToast('교부 완료 — 증빙이 기록됐습니다.', 'success');
      } catch (err) {
        setDeliverError(await disclosureErrorMessage(err, '교부에 실패했습니다.'));
      } finally {
        setDelivering(false);
      }
    })();
  };

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">상품설명서 교부</h1>
          <p className="text-sm text-gray-500 mt-1">
            청약 승인은 <b>교부 증빙이 있어야</b> 통과합니다(완전판매 게이트). 증빙 없이 승인을
            시도하면 서버가 거절합니다.
          </p>
        </div>

        <datalist id="disclosure-products">
          {PRODUCT_CODES.map((code) => <option key={code} value={code} />)}
        </datalist>
        <datalist id="disclosure-banks">
          {BANK_CODES.map((code) => <option key={code} value={code} />)}
        </datalist>

        {/* 미리보기 — 흔적이 남지 않는 쪽 */}
        <section className="bg-white rounded-xl border border-gray-200 p-4 space-y-3"
          data-testid="preview-panel">
          <div>
            <h2 className="font-semibold text-gray-900">미리보기 · 재출력</h2>
            <p className="text-sm text-gray-500 mt-1">
              문서만 내려받습니다. <b>교부 증빙은 남지 않습니다</b> — 고객에게 보여 주기 전
              내용을 확인하거나, 이미 교부한 문서를 다시 뽑을 때 씁니다.
            </p>
          </div>
          <div className="flex flex-wrap items-end gap-2">
            {/* 라벨이 아래 교부 구획의 '상품 코드' 와 같으면 스크린리더에는 같은 칸 둘이다 —
                눈으로는 구획 제목으로 구분되지만 라벨만 읽는 사용자에겐 구분점이 없다. */}
            <Field label="미리보기할 상품 코드">
              <input list="disclosure-products" value={previewCode}
                onChange={(e) => setPreviewCode(e.target.value)}
                className="mt-1 block w-56 rounded border px-3 py-2 font-mono" />
            </Field>
            <button type="button" onClick={() => void preview()}
              disabled={previewCode.trim() === '' || previewing}
              className={`${buttonClass} border border-gray-300 bg-white text-gray-700`}>
              {previewing ? '내려받는 중…' : '미리보기 내려받기'}
            </button>
          </div>
          {previewError && <p role="alert" className="text-sm text-red-600">{previewError}</p>}
        </section>

        {/* 교부 — 증빙이 남는 쪽. 구획을 나눠 오조작을 막는다. */}
        <section className="bg-white rounded-xl border-2 border-amber-300 p-4 space-y-3"
          data-testid="deliver-panel">
          <div>
            <h2 className="font-semibold text-gray-900">교부 (증빙 기록)</h2>
            <p className="text-sm text-gray-500 mt-1">
              문서 발급과 증빙 기록이 <b>한 번에</b> 일어납니다. 되돌리는 경로가 없습니다.
              교부자는 로그인한 계정으로 기록되며 화면에서 지정할 수 없습니다.
            </p>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="상품 코드">
              <input list="disclosure-products" value={productCode}
                onChange={(e) => setProductCode(e.target.value)}
                className={`${inputClass} font-mono`} />
            </Field>

            <Field label="계약자명">
              <input value={contractorName}
                onChange={(e) => setContractorName(e.target.value)} className={inputClass} />
            </Field>

            <Field label="판매 채널">
              <select value={salesChannel} className={inputClass}
                onChange={(e) => setSalesChannel(e.target.value as 'FC' | 'BANCA')}>
                <option value="FC">FC (설계사)</option>
                <option value="BANCA">BANCA (제휴은행)</option>
              </select>
            </Field>

            {/* 방카슈랑스일 때만 그린다 — 안 쓰는 칸을 남겨 두면 무엇이 필수인지 흐려진다. */}
            {bankRequired && (
              <Field label="제휴은행 코드" hint="방카 교부는 제휴은행이 필수입니다">
                <input list="disclosure-banks" value={partnerBankCode}
                  onChange={(e) => setPartnerBankCode(e.target.value)}
                  className={`${inputClass} font-mono`} />
              </Field>
            )}

            <Field label="청약 번호"
              hint="선택 — 넣으면 소유·상품·채널·계약자가 그 청약과 맞는지 서버가 대조합니다">
              <input value={applicationId}
                onChange={(e) => setApplicationId(e.target.value)}
                className={`${inputClass} font-mono`} />
            </Field>
          </div>

          <p className="rounded bg-amber-50 p-3 text-xs text-amber-800">
            청약 번호를 비우면 대조 없이 증빙만 남습니다. 그 증빙은 어느 청약에도 묶이지 않으므로,
            승인 게이트를 열려면 <b>해당 청약 번호를 함께 넣어</b> 교부하세요.
          </p>

          <button type="button" onClick={deliver} disabled={!deliverReady || delivering}
            className={`${buttonClass} bg-amber-600 text-white`}>
            {delivering ? '교부 중…' : '교부하고 증빙 기록'}
          </button>

          {deliverError && <p role="alert" className="text-sm text-red-600">{deliverError}</p>}

          {delivered && (
            <div className="rounded bg-green-50 p-3 text-sm text-green-800" data-testid="deliver-result">
              <p>교부가 기록됐습니다. PDF 를 내려받았습니다.</p>
              {/* 이 해시가 곧 저장된 증빙이다 — 나중에 문서 동일성을 다툴 때 대조 기준이 된다. */}
              <p className="mt-1 break-all font-mono text-xs" data-testid="deliver-sha256">
                SHA-256 {delivered.sha256}
              </p>
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

export default InsuranceDisclosurePage;
