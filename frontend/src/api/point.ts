import api from './axios';

/**
 * 포인트 원장 API.
 *
 * <p>백엔드 설계는 `docs/plan/point-ledger.md`. 화면이 알아야 할 것은 두 가지뿐이다 —
 * 수기 지급에는 <b>사유가 필수</b>이고, 소멸 실행은 <b>미리보기가 기본</b>이라는 것.
 */

/** 수기 지급 결과. `entryId` 가 null 이면 같은 referenceId 로 이미 지급된 건이다(멱등 단축 반환). */
export interface GrantPointResult {
  entryId: number | null;
  lotId: number | null;
  grantedAmount: number;
  remainingBalance: number;
}

/** 소멸 결과. `dryRun` 이 true 면 아무것도 바뀌지 않은 미리보기다. */
export interface ExpirePointResult {
  lotCount: number;
  accountCount: number;
  forfeitedTotal: number;
  dryRun: boolean;
}

export interface PointBalance {
  userId: number;
  available: number;
}

export interface ManualGrantRequest {
  userId: number;
  amount: number;
  /** 멱등 키 — 같은 값으로 두 번 눌러도 한 번만 지급된다(원장 자연키). */
  referenceId: string;
  /** 지급 근거. 없으면 나중에 "왜 이 돈이 여기 있나"에 답할 수 없다. */
  reason: string;
  /** null 이면 무기한. */
  validityDays?: number | null;
}

// 경로는 <b>전체 리터럴</b>로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
export const pointApi = {
  grant: async (body: ManualGrantRequest) =>
    (await api.post<GrantPointResult>('/admin/points/grants', body)).data,

  /** 기본은 미리보기다 — 호출부가 dryRun 을 빠뜨려도 실행되지 않는다. */
  runExpiry: async (dryRun = true, batchSize = 500) =>
    (await api.post<ExpirePointResult>('/admin/points/expiry/run', null, {
      params: { dryRun, batchSize },
    })).data,

  myBalance: async () => (await api.get<PointBalance>('/api/points/me')).data,
};
