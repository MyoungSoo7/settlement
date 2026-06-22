import React, { useState } from 'react';
import {
  reservationApi, ReservationResponse, ReservationCreateRequest, RESERVATION_STATUSES,
} from '@/api/reservation';
import { authApi } from '@/api/auth';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const statusBadge = (s: string) => ({
  REQUESTED:   'bg-yellow-100 text-yellow-800',
  CONFIRMED:   'bg-blue-100 text-blue-800',
  ASSIGNED:    'bg-indigo-100 text-indigo-800',
  IN_PROGRESS: 'bg-purple-100 text-purple-800',
  COMPLETED:   'bg-green-100 text-green-800',
  CANCELED:    'bg-gray-200 text-gray-700',
}[s] ?? 'bg-gray-100 text-gray-800');

const emptyForm: ReservationCreateRequest = {
  scheduledDate: '',
  siteAddress: '',
  sitePassword: '',
  siteManagerName: '',
  siteManagerPhone: '',
  productId: null,
  woodSpecies: '',
  brand: '',
  productName: '',
  productSize: '',
  constructionArea: 0,
  fieldMeasured: false,
  expansion: false,
  expansionArea: null,
  newFloor: false,
  baseboard: false,
  protectionWork: false,
  protectionArea: null,
  note: '',
};

/* ── 예약 카드(목록 아이템) ── */
const ReservationItem: React.FC<{
  r: ReservationResponse;
  isAdmin: boolean;
  onAction: (fn: () => Promise<ReservationResponse>) => void;
  busy: boolean;
}> = ({ r, isAdmin, onAction, busy }) => {
  const [techId, setTechId] = useState<number>(0);
  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <div className="flex justify-between items-start mb-2">
        <div>
          <span className="font-semibold text-gray-900">#{r.id}</span>
          <span className="ml-2 text-sm text-gray-500">{r.scheduledDate}</span>
        </div>
        <span className={`px-2.5 py-1 rounded-full text-xs font-semibold ${statusBadge(r.status)}`}>{r.status}</span>
      </div>
      <div className="text-sm text-gray-700 space-y-0.5">
        <p>📍 {r.siteAddress}</p>
        <p>👤 {r.siteManagerName} · {r.siteManagerPhone}</p>
        <p>🪵 {[r.brand, r.productName, r.woodSpecies, r.productSize].filter(Boolean).join(' / ') || '제품정보 없음'}</p>
        <p>📐 시공면적 {r.constructionArea}㎡{r.expansion ? ` · 확장 ${r.expansionArea ?? 0}㎡` : ''}</p>
        {r.technicianId && <p>🔧 배정기사 #{r.technicianId}</p>}
        {r.note && <p className="text-gray-500">📝 {r.note}</p>}
      </div>

      {isAdmin && (
        <div className="mt-3 pt-3 border-t border-gray-100 flex flex-wrap items-center gap-2">
          {r.status === 'REQUESTED' && (
            <button disabled={busy} onClick={() => onAction(() => reservationApi.confirm(r.id))}
              className="px-3 py-1.5 bg-blue-600 text-white rounded text-xs font-semibold hover:bg-blue-700 disabled:opacity-40">확인</button>
          )}
          {r.status === 'CONFIRMED' && (
            <>
              <input type="number" min={1} placeholder="기사ID" value={techId || ''}
                onChange={(e) => setTechId(Number(e.target.value))}
                className="w-24 px-2 py-1.5 border border-gray-300 rounded text-xs" />
              <button disabled={busy || !techId} onClick={() => onAction(() => reservationApi.assign(r.id, techId))}
                className="px-3 py-1.5 bg-indigo-600 text-white rounded text-xs font-semibold hover:bg-indigo-700 disabled:opacity-40">기사배정</button>
            </>
          )}
          {r.status === 'ASSIGNED' && (
            <>
              <input type="number" min={1} placeholder="재배정ID" value={techId || ''}
                onChange={(e) => setTechId(Number(e.target.value))}
                className="w-24 px-2 py-1.5 border border-gray-300 rounded text-xs" />
              <button disabled={busy || !techId} onClick={() => onAction(() => reservationApi.reassign(r.id, techId))}
                className="px-3 py-1.5 bg-amber-600 text-white rounded text-xs font-semibold hover:bg-amber-700 disabled:opacity-40">재배정</button>
              <button disabled={busy} onClick={() => onAction(() => reservationApi.start(r.id))}
                className="px-3 py-1.5 bg-purple-600 text-white rounded text-xs font-semibold hover:bg-purple-700 disabled:opacity-40">시공시작</button>
            </>
          )}
          {r.status === 'IN_PROGRESS' && (
            <button disabled={busy} onClick={() => onAction(() => reservationApi.complete(r.id))}
              className="px-3 py-1.5 bg-green-600 text-white rounded text-xs font-semibold hover:bg-green-700 disabled:opacity-40">시공완료</button>
          )}
          {r.status !== 'COMPLETED' && r.status !== 'CANCELED' && (
            <button disabled={busy} onClick={() => onAction(() => reservationApi.cancel(r.id))}
              className="px-3 py-1.5 bg-red-100 text-red-700 rounded text-xs font-semibold hover:bg-red-200 disabled:opacity-40">취소</button>
          )}
        </div>
      )}
    </div>
  );
};

const ReservationPage: React.FC = () => {
  const user = authApi.getCurrentUser();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  const [tab, setTab] = useState<'register' | 'mine' | 'admin'>(isAdmin ? 'admin' : 'register');
  const [form, setForm] = useState<ReservationCreateRequest>(emptyForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [list, setList] = useState<ReservationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  // admin 필터
  const [fDate, setFDate] = useState('');
  const [fStatus, setFStatus] = useState('');

  const set = <K extends keyof ReservationCreateRequest>(k: K, v: ReservationCreateRequest[K]) =>
    setForm((f) => ({ ...f, [k]: v }));

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true); setError(null); setNotice(null);
    try {
      const created = await reservationApi.register({
        ...form,
        productId: form.productId || null,
        expansionArea: form.expansion ? form.expansionArea : null,
        protectionArea: form.protectionWork ? form.protectionArea : null,
      });
      setNotice(`예약 등록 완료 — #${created.id} (${created.status})`);
      setForm(emptyForm);
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('예약 등록은 업체(COMPANY) 회원 또는 관리자만 가능합니다. ADMIN 으로 로그인해 시연하세요.');
      } else {
        setError(err.response?.data?.message || '예약 등록에 실패했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const loadMine = async () => {
    setLoading(true); setError(null);
    try { setList(await reservationApi.getMine()); }
    catch (err: any) {
      setError(err.response?.status === 403
        ? '내 예약은 업체(COMPANY) 회원만 조회할 수 있습니다.'
        : (err.response?.data?.message || '조회에 실패했습니다.'));
      setList([]);
    } finally { setLoading(false); }
  };

  const loadAdmin = async () => {
    setLoading(true); setError(null);
    try { setList(await reservationApi.adminSearch(fDate || undefined, fStatus || undefined)); }
    catch (err: any) {
      setError(err.response?.status === 403
        ? '관리자 대시보드는 ADMIN/MANAGER 만 조회할 수 있습니다.'
        : (err.response?.data?.message || '조회에 실패했습니다.'));
      setList([]);
    } finally { setLoading(false); }
  };

  const runAction = async (fn: () => Promise<ReservationResponse>) => {
    setBusy(true); setError(null); setNotice(null);
    try {
      await fn();
      setNotice('상태가 변경되었습니다.');
      isAdmin ? await loadAdmin() : await loadMine();
    } catch (err: any) {
      setError(err.response?.data?.message || '상태 변경에 실패했습니다.');
    } finally { setBusy(false); }
  };

  const TabBtn: React.FC<{ id: typeof tab; label: string; onClick?: () => void }> = ({ id, label, onClick }) => (
    <button
      onClick={() => { setTab(id); setError(null); setNotice(null); onClick?.(); }}
      className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
        tab === id ? 'bg-blue-600 text-white' : 'bg-white text-gray-600 hover:bg-blue-50 border border-gray-200'
      }`}
    >{label}</button>
  );

  const inputCls = 'w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500';
  const checks: [keyof ReservationCreateRequest, string][] = [
    ['fieldMeasured', '실측완료'], ['expansion', '확장'], ['newFloor', '신규시공'],
    ['baseboard', '걸레받이'], ['protectionWork', '보양작업'],
  ];

  return (
    <div className="min-h-screen bg-gradient-to-br from-orange-50 to-amber-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto space-y-6">
        <div className="text-center">
          <h1 className="text-3xl font-bold text-gray-900">예약하기</h1>
          <p className="mt-1 text-sm text-gray-500">마루 시공 예약 등록 · 기사 배정 · 진행 관리</p>
        </div>

        <div className="flex flex-wrap gap-2 justify-center">
          <TabBtn id="register" label="예약 등록" />
          <TabBtn id="mine" label="내 예약" onClick={loadMine} />
          {isAdmin && <TabBtn id="admin" label="관리자 대시보드" onClick={loadAdmin} />}
        </div>

        {error && <div className="bg-red-50 border border-red-200 rounded-lg p-3"><p className="text-red-800 text-sm">{error}</p></div>}
        {notice && <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-3"><p className="text-emerald-800 text-sm">{notice}</p></div>}

        {/* 등록 */}
        {tab === 'register' && (
          <Card title="시공 예약 등록">
            <form className="space-y-4" onSubmit={handleRegister}>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">시공 일정 *</label>
                  <input type="date" required value={form.scheduledDate}
                    onChange={(e) => set('scheduledDate', e.target.value)} className={inputCls} />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">시공 면적(㎡) *</label>
                  <input type="number" min={0.1} step="0.1" required value={form.constructionArea || ''}
                    onChange={(e) => set('constructionArea', Number(e.target.value))} className={inputCls} />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">현장 주소 *</label>
                <input type="text" required value={form.siteAddress}
                  onChange={(e) => set('siteAddress', e.target.value)} className={inputCls} placeholder="시공 현장 주소" />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">현장 담당자 *</label>
                  <input type="text" required value={form.siteManagerName}
                    onChange={(e) => set('siteManagerName', e.target.value)} className={inputCls} />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">담당자 연락처 *</label>
                  <input type="text" required value={form.siteManagerPhone}
                    onChange={(e) => set('siteManagerPhone', e.target.value)} className={inputCls} placeholder="010-1234-5678" />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">현장 비밀번호</label>
                  <input type="text" value={form.sitePassword}
                    onChange={(e) => set('sitePassword', e.target.value)} className={inputCls} />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1.5">상품 ID</label>
                  <input type="number" min={1} value={form.productId || ''}
                    onChange={(e) => set('productId', e.target.value ? Number(e.target.value) : null)} className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div><label className="block text-xs text-gray-500 mb-1">브랜드</label>
                  <input value={form.brand} onChange={(e) => set('brand', e.target.value)} className={inputCls} /></div>
                <div><label className="block text-xs text-gray-500 mb-1">수종</label>
                  <input value={form.woodSpecies} onChange={(e) => set('woodSpecies', e.target.value)} className={inputCls} /></div>
                <div><label className="block text-xs text-gray-500 mb-1">제품명</label>
                  <input value={form.productName} onChange={(e) => set('productName', e.target.value)} className={inputCls} /></div>
                <div><label className="block text-xs text-gray-500 mb-1">규격</label>
                  <input value={form.productSize} onChange={(e) => set('productSize', e.target.value)} className={inputCls} /></div>
              </div>

              <div className="flex flex-wrap gap-4 bg-gray-50 rounded-lg p-3">
                {checks.map(([k, label]) => (
                  <label key={k} className="flex items-center gap-1.5 text-sm text-gray-700">
                    <input type="checkbox" checked={!!form[k]}
                      onChange={(e) => set(k, e.target.checked as any)} className="rounded" />
                    {label}
                  </label>
                ))}
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {form.expansion && (
                  <div><label className="block text-sm font-medium text-gray-700 mb-1.5">확장 면적(㎡)</label>
                    <input type="number" min={0} step="0.1" value={form.expansionArea || ''}
                      onChange={(e) => set('expansionArea', Number(e.target.value))} className={inputCls} /></div>
                )}
                {form.protectionWork && (
                  <div><label className="block text-sm font-medium text-gray-700 mb-1.5">보양 면적(㎡)</label>
                    <input type="number" min={0} step="0.1" value={form.protectionArea || ''}
                      onChange={(e) => set('protectionArea', Number(e.target.value))} className={inputCls} /></div>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1.5">비고</label>
                <textarea rows={2} value={form.note}
                  onChange={(e) => set('note', e.target.value)} className={inputCls} />
              </div>

              {submitting ? <Spinner size="sm" message="등록 중..." /> : (
                <button type="submit" className="w-full bg-orange-600 text-white py-3 rounded-lg font-semibold hover:bg-orange-700 transition-colors">
                  예약 등록
                </button>
              )}
              {!isAdmin && (
                <p className="text-xs text-gray-400 text-center">※ 등록은 업체(COMPANY) 회원 또는 ADMIN 권한이 필요합니다.</p>
              )}
            </form>
          </Card>
        )}

        {/* 내 예약 */}
        {tab === 'mine' && (
          <Card title="내 예약 현황">
            {loading ? <Spinner size="md" message="불러오는 중..." /> : list.length === 0 ? (
              <p className="text-sm text-gray-400 text-center py-6">예약이 없습니다.</p>
            ) : (
              <div className="space-y-3">
                {list.map((r) => <ReservationItem key={r.id} r={r} isAdmin={isAdmin} onAction={runAction} busy={busy} />)}
              </div>
            )}
          </Card>
        )}

        {/* 관리자 대시보드 */}
        {tab === 'admin' && isAdmin && (
          <Card title="관리자 대시보드">
            <div className="flex flex-wrap items-end gap-3 mb-4">
              <div>
                <label className="block text-xs text-gray-500 mb-1">시공일자</label>
                <input type="date" value={fDate} onChange={(e) => setFDate(e.target.value)}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm" />
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">상태</label>
                <select value={fStatus} onChange={(e) => setFStatus(e.target.value)}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm">
                  <option value="">전체</option>
                  {RESERVATION_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <button onClick={loadAdmin}
                className="px-5 py-2 bg-gray-800 text-white rounded-lg text-sm font-semibold hover:bg-gray-900 transition-colors">조회</button>
            </div>
            {loading ? <Spinner size="md" message="불러오는 중..." /> : list.length === 0 ? (
              <p className="text-sm text-gray-400 text-center py-6">조회된 예약이 없습니다.</p>
            ) : (
              <div className="space-y-3">
                {list.map((r) => <ReservationItem key={r.id} r={r} isAdmin onAction={runAction} busy={busy} />)}
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  );
};

export default ReservationPage;
