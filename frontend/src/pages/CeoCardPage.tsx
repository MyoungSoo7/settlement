import React, { useCallback, useEffect, useState } from 'react';
import {
  cardApi,
  type CardAccount,
  type CardStatus,
  type CorporateCard,
} from '@/api/card';
import { apiErrorMessage } from '@/lib/apiError';
import { formatDecimal } from '@/lib/decimal';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 법인카드 콘솔 (CEO 메뉴).
 *
 * <p>카드계정(마스터 한도)과 임직원 카드(서브한도)를 한 화면에서 다룬다. 조작 권한(조직
 * OWNER/MANAGER/STAFF)은 서버가 멤버십 프로젝션으로 판정하므로, 이 화면은 역할을 묻지 않고
 * 403 응답을 그대로 보여 준다 — 화면이 권한을 흉내 내면 서버와 어긋난 순간 거짓말이 된다.
 *
 * <p>마스터 한도에는 산정 근거(평판등급·재원·인정비율)를 반드시 함께 그린다. "왜 이 한도인가"에
 * 답할 수 없는 여신 화면은 그 자체로 CS 비용이다. Σ서브한도는 <b>해지만 제외</b>하고 집계한다 —
 * 정지 카드도 한도를 점유한다(복직 시 돌아갈 자리를 지키는 도메인 규칙을 화면도 따른다).
 */

const ACCOUNT_STATUS_BADGE: Record<CardAccount['status'], { label: string; cls: string }> = {
  SCREENING:  { label: '심사중', cls: 'bg-blue-100 text-blue-800' },
  ACTIVE:     { label: '정상', cls: 'bg-green-100 text-green-800' },
  SUSPENDED:  { label: '정지', cls: 'bg-amber-100 text-amber-800' },
  DELINQUENT: { label: '연체', cls: 'bg-red-100 text-red-800' },
  CLOSED:     { label: '해지', cls: 'bg-gray-200 text-gray-600' },
  REJECTED:   { label: '거절', cls: 'bg-red-100 text-red-800' },
};

const CARD_STATUS_BADGE: Record<CardStatus, { label: string; cls: string }> = {
  ISSUED:    { label: '사용중', cls: 'bg-green-100 text-green-800' },
  SUSPENDED: { label: '정지', cls: 'bg-amber-100 text-amber-800' },
  CANCELED:  { label: '해지', cls: 'bg-gray-200 text-gray-600' },
};

const StatusBadge: React.FC<{ badge: { label: string; cls: string } }> = ({ badge }) => (
  <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${badge.cls}`}>{badge.label}</span>
);

const CeoCardPage: React.FC = () => {
  const { showToast } = useToast();

  const [myCards, setMyCards] = useState<CorporateCard[] | null>(null);
  const [myCardsError, setMyCardsError] = useState<string | null>(null);

  const [accountIdInput, setAccountIdInput] = useState('');
  const [orgIdInput, setOrgIdInput] = useState('');
  const [account, setAccount] = useState<CardAccount | null>(null);
  const [cards, setCards] = useState<CorporateCard[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [holderInput, setHolderInput] = useState('');
  const [subLimitInput, setSubLimitInput] = useState('');

  useEffect(() => {
    cardApi.myCards()
      .then(setMyCards)
      .catch((err) => {
        setMyCards([]);
        setMyCardsError(apiErrorMessage(err, '내 카드를 불러오지 못했습니다.'));
      });
  }, []);

  const loadAccount = useCallback(async (accountId: number) => {
    setLoading(true);
    setError(null);
    try {
      const [loadedAccount, loadedCards] = await Promise.all([
        cardApi.getAccount(accountId),
        cardApi.listCards(accountId),
      ]);
      setAccount(loadedAccount);
      setCards(loadedCards);
    } catch (err) {
      setAccount(null);
      setCards(null);
      setError(apiErrorMessage(err, '카드계정을 조회하지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshCards = useCallback(async (accountId: number) => {
    try {
      setCards(await cardApi.listCards(accountId));
    } catch (err) {
      setError(apiErrorMessage(err, '카드 목록을 다시 읽지 못했습니다.'));
    }
  }, []);

  const handleLookup = async () => {
    const accountId = Number(accountIdInput);
    if (!accountIdInput || !Number.isFinite(accountId)) {
      showToast('카드계정 ID 를 입력하세요.', 'warning');
      return;
    }
    await loadAccount(accountId);
  };

  const handleOpenAccount = async () => {
    const organizationId = Number(orgIdInput);
    if (!orgIdInput || !Number.isFinite(organizationId)) {
      showToast('조직 ID 를 입력하세요.', 'warning');
      return;
    }
    if (!window.confirm(
      `조직 ${organizationId} 의 법인카드 계정을 개설합니다.\n`
      + '한도는 정산 재원과 평판등급으로 심사되며, 요청 즉시 부여되지 않을 수 있습니다. 진행할까요?')) return;

    setBusy(true);
    setError(null);
    try {
      const opened = await cardApi.openAccount(organizationId);
      setAccount(opened);
      setAccountIdInput(String(opened.id));
      await refreshCards(opened.id);
      showToast('카드계정 개설을 요청했습니다.', 'success');
    } catch (err) {
      setError(apiErrorMessage(err, '카드계정 개설에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const handleIssue = async () => {
    if (!account) return;
    const holderUserId = Number(holderInput);
    const subLimit = Number(subLimitInput);
    if (!holderInput || !Number.isFinite(holderUserId)) {
      showToast('임직원 사용자 ID 를 입력하세요.', 'warning');
      return;
    }
    if (!subLimitInput || !Number.isFinite(subLimit) || subLimit < 0) {
      showToast('서브한도를 0 이상으로 입력하세요.', 'warning');
      return;
    }

    setBusy(true);
    setError(null);
    try {
      await cardApi.issueCard(account.id, { holderUserId, subLimit });
      showToast('카드를 발급했습니다.', 'success');
      setHolderInput('');
      setSubLimitInput('');
      await refreshCards(account.id);
    } catch (err) {
      setError(apiErrorMessage(err, '카드 발급에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const handleChangeLimit = async (target: CorporateCard) => {
    const raw = window.prompt('새 서브한도(원)를 입력하세요.', String(target.subLimit));
    if (raw === null) return;
    const subLimit = Number(raw);
    if (!Number.isFinite(subLimit) || subLimit < 0) {
      showToast('서브한도는 0 이상의 숫자여야 합니다.', 'warning');
      return;
    }

    setBusy(true);
    try {
      await cardApi.changeSubLimit(target.id, subLimit);
      showToast('서브한도를 변경했습니다.', 'success');
      if (account) await refreshCards(account.id);
    } catch (err) {
      setError(apiErrorMessage(err, '서브한도 변경에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const changeStatus = async (target: CorporateCard, status: CardStatus, promptLabel: string) => {
    // 사유는 감사 기록이다 — 서버도 400 으로 끊지만, 화면이 먼저 사유 없는 조작을 막는다.
    const reason = window.prompt(promptLabel);
    if (reason === null || reason.trim() === '') return;

    setBusy(true);
    try {
      await cardApi.changeStatus(target.id, { status, reason: reason.trim() });
      showToast('카드 상태를 변경했습니다.', 'success');
      if (account) await refreshCards(account.id);
    } catch (err) {
      setError(apiErrorMessage(err, '카드 상태 변경에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const handleCancel = async (target: CorporateCard) => {
    if (!window.confirm(
      `${target.maskedCardNo} 카드를 해지합니다.\n`
      + '해지는 되돌릴 수 없습니다. 해지된 카드의 서브한도는 계정 한도로 반환됩니다. 진행할까요?')) return;
    await changeStatus(target, 'CANCELED', '해지 사유를 입력하세요.');
  };

  // 해지만 제외 — 정지 카드도 한도를 점유한다(복직 시 돌아갈 한도를 지킨다).
  const activeSubLimitSum = (cards ?? [])
    .filter((c) => c.status !== 'CANCELED')
    .reduce((sum, c) => sum + c.subLimit, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">법인카드</h1>
        <p className="text-sm text-gray-500 mt-1">
          카드계정(마스터 한도)과 임직원 카드(서브한도)를 관리합니다. 조작 권한은 조직
          멤버십(OWNER/MANAGER)으로 서버가 판정합니다.
        </p>
      </div>

      {/* ── 내 카드 ── */}
      <div className="bg-white rounded-xl border border-gray-200 p-4">
        <h3 className="font-bold text-gray-900 mb-3">내 카드</h3>
        {myCardsError && <p className="text-sm text-amber-700">{myCardsError}</p>}
        {myCards === null && <Spinner size="sm" message="내 카드 읽는 중..." />}
        {myCards !== null && myCards.length === 0 && !myCardsError && (
          <p className="text-sm text-gray-400">발급받은 카드가 없습니다.</p>
        )}
        {myCards !== null && myCards.length > 0 && (
          <ul className="space-y-2">
            {myCards.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-3 text-sm">
                <span className="font-mono text-gray-800">{c.maskedCardNo}</span>
                <span className="text-gray-500">한도 {formatDecimal(c.subLimit)}원</span>
                <StatusBadge badge={CARD_STATUS_BADGE[c.status]} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* ── 계정 조회 · 개설 ── */}
      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="card-account-id" className="block text-xs font-medium text-gray-600 mb-1">카드계정 ID</label>
          <input id="card-account-id" type="number" min={1} value={accountIdInput}
            onChange={(e) => setAccountIdInput(e.target.value)} className="input w-40" />
        </div>
        <button onClick={handleLookup} disabled={busy}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
          계정 조회
        </button>

        <div className="ml-auto flex items-end gap-3">
          <div>
            <label htmlFor="card-org-id" className="block text-xs font-medium text-gray-600 mb-1">조직 ID</label>
            <input id="card-org-id" type="number" min={1} value={orgIdInput}
              onChange={(e) => setOrgIdInput(e.target.value)} className="input w-40" />
          </div>
          <button onClick={handleOpenAccount} disabled={busy}
            className="px-4 py-2 bg-gray-800 text-white text-sm font-semibold rounded-lg hover:bg-gray-900 disabled:opacity-50">
            계정 개설
          </button>
        </div>
      </div>

      {error && <p className="py-4 text-center text-red-600">{error}</p>}
      {loading && <div className="py-12 flex justify-center"><Spinner size="lg" message="카드계정 읽는 중..." /></div>}

      {/* ── 카드계정 ── */}
      {account && !loading && (
        <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <h3 className="font-bold text-gray-900">카드계정 #{account.id}</h3>
            <StatusBadge badge={ACCOUNT_STATUS_BADGE[account.status]} />
            <span className="text-sm text-gray-500">조직 {account.organizationId} · 셀러 {account.sellerId}</span>
          </div>

          {account.rejectReason && (
            <p className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
              {account.rejectReason}
            </p>
          )}

          <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
            <div>
              <p className="text-xs text-gray-500">마스터 한도(원)</p>
              <p className="font-bold text-gray-900 text-lg">{formatDecimal(account.masterLimit)}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">평판등급</p>
              <p className="font-semibold text-gray-900">{account.reputationGrade ?? '-'}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">정산 재원(미지급)</p>
              <p className="font-semibold text-gray-900">{formatDecimal(account.sellerPayable) ?? '-'}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">홀드백 유보</p>
              <p className="font-semibold text-gray-900">{formatDecimal(account.holdbackPayable) ?? '-'}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">인정비율</p>
              <p className="font-semibold text-gray-900">
                {account.appliedRatio !== null ? `${Math.round(account.appliedRatio * 100)}%` : '-'}
              </p>
            </div>
          </div>

          <p className="text-xs text-gray-500">
            한도 = 재원 × 인정비율 × 평판 계수(원 단위 절사). 재원은 계정계(GL) 통제계정 잔액이
            정본이라 정산·조정이 반영될 때마다 일 배치로 재산정됩니다.
          </p>

          {/* ── 계정 카드 목록 ── */}
          <div className="border-t border-gray-100 pt-4 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h4 className="font-bold text-gray-900">임직원 카드 {cards?.length ?? 0}장</h4>
              <p className="text-sm text-gray-600">
                서브한도 합계 <b>{formatDecimal(activeSubLimitSum)}</b>원
                <span className="text-xs text-gray-400 ml-1">(해지 제외 — 정지 카드도 한도를 점유합니다)</span>
              </p>
            </div>

            <div className="flex flex-wrap items-end gap-3 bg-gray-50 rounded-lg p-3">
              <div>
                <label htmlFor="card-holder-id" className="block text-xs font-medium text-gray-600 mb-1">임직원 사용자 ID</label>
                <input id="card-holder-id" type="number" min={1} value={holderInput}
                  onChange={(e) => setHolderInput(e.target.value)} className="input w-40" />
              </div>
              <div>
                <label htmlFor="card-sub-limit" className="block text-xs font-medium text-gray-600 mb-1">서브한도(원)</label>
                <input id="card-sub-limit" type="number" min={0} step={10000} value={subLimitInput}
                  onChange={(e) => setSubLimitInput(e.target.value)} className="input w-40" />
              </div>
              <button onClick={handleIssue} disabled={busy}
                className="px-4 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
                카드 발급
              </button>
            </div>

            {cards && cards.length === 0 && (
              <p className="py-6 text-center text-gray-400">이 계정에 발급된 카드가 없습니다.</p>
            )}

            {cards && cards.length > 0 && (
              <div className="space-y-2">
                {cards.map((c) => (
                  <div key={c.id} className="flex flex-wrap items-center gap-3 border border-gray-100 rounded-lg px-3 py-2 text-sm">
                    <span className="font-mono text-gray-800">{c.maskedCardNo}</span>
                    <span className="text-gray-500">사용자 {c.holderUserId}</span>
                    <span className="text-gray-700">한도 {formatDecimal(c.subLimit)}원</span>
                    <StatusBadge badge={CARD_STATUS_BADGE[c.status]} />
                    <span className="ml-auto flex gap-2">
                      {c.status !== 'CANCELED' && (
                        <button onClick={() => handleChangeLimit(c)} disabled={busy}
                          className="px-3 py-1 text-xs font-semibold rounded-lg border border-gray-300 hover:bg-gray-50 disabled:opacity-50">
                          한도 변경
                        </button>
                      )}
                      {c.status === 'ISSUED' && (
                        <button onClick={() => changeStatus(c, 'SUSPENDED', '정지 사유를 입력하세요.')} disabled={busy}
                          className="px-3 py-1 text-xs font-semibold rounded-lg border border-amber-300 text-amber-700 hover:bg-amber-50 disabled:opacity-50">
                          정지
                        </button>
                      )}
                      {c.status === 'SUSPENDED' && (
                        <button onClick={() => changeStatus(c, 'ISSUED', '재개 사유를 입력하세요.')} disabled={busy}
                          className="px-3 py-1 text-xs font-semibold rounded-lg border border-green-300 text-green-700 hover:bg-green-50 disabled:opacity-50">
                          재개
                        </button>
                      )}
                      {c.status !== 'CANCELED' && (
                        <button onClick={() => handleCancel(c)} disabled={busy}
                          className="px-3 py-1 text-xs font-semibold rounded-lg border border-red-300 text-red-700 hover:bg-red-50 disabled:opacity-50">
                          해지
                        </button>
                      )}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default CeoCardPage;
