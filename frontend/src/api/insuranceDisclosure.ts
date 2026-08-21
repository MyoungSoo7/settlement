import api from './axios';

/**
 * 보험 상품설명서 — insurance-service {@code ProductDisclosureController} (`/api/insurance`).
 *
 * <p>교부(deliver)는 <b>문서 발급과 증빙 기록이 한 행위</b>다. 이 증빙이 없으면 청약 승인이
 * 완전판매 게이트에 막혀 409 로 거절된다({@code DisclosureNotDeliveredException}).
 * 즉 교부 화면이 없는 동안 승인은 UI 로 통과시킬 수 없었다.
 *
 * <p><b>교부자를 보내지 않는다.</b> 서버가 JWT 주체에서만 파생한다 — 교부 증빙은 완전판매를
 * 증명하는 규제 문서라 "누가 교부했는가"를 본문으로 받으면 증빙이 성립하지 않는다.
 *
 * <p>두 엔드포인트 모두 <b>PDF 바이트</b>를 돌려준다. 그래서 오류 응답도 Blob 으로 오는데,
 * 그대로 두면 서버가 준 사유(게이트 위반·계약자 불일치 등)가 통째로 사라진다 —
 * {@link disclosureErrorMessage} 가 Blob 을 풀어 그 문구를 되살린다.
 */

export interface RenderedDisclosure {
  blob: Blob;
  /** 문서 해시. 교부 시에는 이 값이 곧 <b>저장된 증빙 해시</b>다. */
  sha256: string;
  fileName: string;
}

export interface DeliverDisclosureInput {
  /** 청약 경유 교부면 함께 보낸다 — 서버가 소유·상품·채널·계약자 일치를 대조한다. */
  applicationId?: string;
  productCode: string;
  salesChannel: 'FC' | 'BANCA';
  /** BANCA 교부 시 필수 — 도메인이 강제한다. */
  partnerBankCode?: string;
  contractorName: string;
}

const fileNameOf = (headers: unknown, productCode: string): string => {
  const disposition = String((headers as Record<string, unknown>)?.['content-disposition'] ?? '');
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return match ? decodeURIComponent(match[1]) : `disclosure-${productCode}.pdf`;
};

const rendered = (
  response: { data: Blob; headers: unknown }, productCode: string,
): RenderedDisclosure => ({
  blob: response.data,
  sha256: String((response.headers as Record<string, unknown>)?.['x-document-sha256'] ?? ''),
  fileName: fileNameOf(response.headers, productCode),
});

/**
 * PDF 요청이 실패했을 때 서버 문구를 되살린다.
 *
 * <p>{@code responseType: 'blob'} 이면 axios 는 <b>오류 본문도 Blob 으로</b> 준다. 그래서
 * 평소 쓰는 {@code apiErrorMessage} 는 `data.message` 를 찾지 못하고 기본 문구로 떨어진다 —
 * "완전판매 게이트 미통과", "계약자가 청약과 다릅니다" 같은 <b>조치에 필요한 사유</b>가 사라진다.
 * Blob 을 텍스트로 풀어 JSON 의 `error` 를 꺼낸다(서버 핸들러가 그 키로 준다).
 *
 * <p>비동기인 이유도 여기 있다 — Blob 읽기가 비동기라 동기 헬퍼로는 만들 수 없다.
 */
export const disclosureErrorMessage = async (err: unknown, fallback: string): Promise<string> => {
  const data = (err as { response?: { data?: unknown } })?.response?.data;
  if (data instanceof Blob) {
    try {
      const text = await data.text();
      const parsed: unknown = JSON.parse(text);
      const message = (parsed as { error?: unknown; message?: unknown });
      if (typeof message?.error === 'string' && message.error.length > 0) return message.error;
      if (typeof message?.message === 'string' && message.message.length > 0) return message.message;
      // JSON 이 아니거나 모양이 다르면 원문을 쓰지 않는다 — HTML 오류 페이지가 통째로 뜬다.
    } catch {
      // 파싱 실패는 조용히 기본 문구로. 여기서 던지면 원래 오류가 파싱 오류에 가려진다.
    }
  }
  return fallback;
};

export const insuranceDisclosureApi = {
  /** 미리보기·재출력 — <b>증빙은 남지 않는다</b>. */
  preview: async (productCode: string): Promise<RenderedDisclosure> => {
    const response = await api.get<Blob>(
      `/api/insurance/products/${encodeURIComponent(productCode)}/disclosure`,
      { responseType: 'blob' });
    return rendered(response, productCode);
  },

  /**
   * 교부 — 문서 발급 + 증빙 기록. <b>되돌리는 경로가 없다.</b>
   * 응답 PDF 의 SHA-256 이 곧 저장된 증빙 해시다.
   */
  deliver: async (input: DeliverDisclosureInput): Promise<RenderedDisclosure> => {
    const response = await api.post<Blob>('/api/insurance/disclosures', input,
      { responseType: 'blob' });
    return rendered(response, input.productCode);
  },
};
