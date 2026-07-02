import React, { useEffect, useState } from 'react';
import {
  commonCodeApi, CommonCodeGroup, CommonCode,
} from '@/api/system';
import Spinner from '@/components/Spinner';

const blankGroup = { groupCode: '', name: '', description: '' };
const blankCode = { code: '', label: '', sortOrder: 0, extra1: '' };

const CommonCodeManagementPage: React.FC = () => {
  const [groups, setGroups] = useState<CommonCodeGroup[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [codes, setCodes] = useState<CommonCode[]>([]);
  const [loading, setLoading] = useState(true);
  const [codesLoading, setCodesLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [groupForm, setGroupForm] = useState(blankGroup);
  const [codeForm, setCodeForm] = useState(blankCode);
  const [savingGroup, setSavingGroup] = useState(false);
  const [savingCode, setSavingCode] = useState(false);

  const loadGroups = async () => {
    try {
      const list = await commonCodeApi.getGroups();
      setGroups(list);
      if (list.length && !selected) selectGroup(list[0].groupCode);
    } catch {
      setError('공통코드 그룹을 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const selectGroup = async (groupCode: string) => {
    setSelected(groupCode);
    setCodesLoading(true);
    try {
      setCodes(await commonCodeApi.getCodes(groupCode));
    } catch {
      setCodes([]);
    } finally {
      setCodesLoading(false);
    }
  };

  useEffect(() => { loadGroups(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleCreateGroup = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingGroup(true);
    try {
      const created = await commonCodeApi.createGroup({
        groupCode: groupForm.groupCode.trim().toUpperCase(),
        name: groupForm.name.trim(),
        description: groupForm.description.trim() || undefined,
      });
      setGroups((g) => [...g, created]);
      setGroupForm(blankGroup);
      selectGroup(created.groupCode);
    } catch (err: any) {
      alert(err.response?.data?.message || '그룹 생성 실패');
    } finally {
      setSavingGroup(false);
    }
  };

  const handleToggleGroup = async (g: CommonCodeGroup) => {
    try {
      const updated = await commonCodeApi.updateGroup(g.groupCode, {
        name: g.name, description: g.description ?? undefined, active: !g.active,
      });
      setGroups((list) => list.map((x) => (x.groupCode === g.groupCode ? updated : x)));
    } catch { alert('그룹 수정 실패'); }
  };

  const handleDeleteGroup = async (groupCode: string) => {
    if (!window.confirm(`그룹 "${groupCode}" 과 하위 코드를 모두 삭제하시겠습니까?`)) return;
    try {
      await commonCodeApi.deleteGroup(groupCode);
      setGroups((list) => list.filter((x) => x.groupCode !== groupCode));
      if (selected === groupCode) { setSelected(null); setCodes([]); }
    } catch { alert('그룹 삭제 실패'); }
  };

  const handleCreateCode = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    setSavingCode(true);
    try {
      const created = await commonCodeApi.createCode({
        groupCode: selected,
        code: codeForm.code.trim().toUpperCase(),
        label: codeForm.label.trim(),
        sortOrder: Number(codeForm.sortOrder) || 0,
        extra1: codeForm.extra1.trim() || undefined,
      });
      setCodes((c) => [...c, created].sort((a, b) => a.sortOrder - b.sortOrder));
      setCodeForm(blankCode);
    } catch (err: any) {
      alert(err.response?.data?.message || '코드 생성 실패');
    } finally {
      setSavingCode(false);
    }
  };

  const handleToggleCode = async (c: CommonCode) => {
    try {
      const updated = await commonCodeApi.updateCode(c.id, {
        label: c.label, sortOrder: c.sortOrder, active: !c.active, extra1: c.extra1 ?? undefined,
      });
      setCodes((list) => list.map((x) => (x.id === c.id ? updated : x)));
    } catch { alert('코드 수정 실패'); }
  };

  const handleDeleteCode = async (id: number) => {
    if (!window.confirm('이 코드를 삭제하시겠습니까?')) return;
    try {
      await commonCodeApi.deleteCode(id);
      setCodes((list) => list.filter((x) => x.id !== id));
    } catch { alert('코드 삭제 실패'); }
  };

  if (loading) return <div className="py-20 flex justify-center"><Spinner size="lg" message="공통코드 로드 중..." /></div>;
  if (error)   return <p className="text-red-600 py-10 text-center">{error}</p>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">공통코드 관리</h1>
        <p className="text-sm text-gray-500 mt-1">코드 그룹과 그 하위 코드 항목을 관리합니다.</p>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        {/* 그룹 목록 */}
        <div className="xl:col-span-2 bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-900">코드 그룹</h3>
            <span className="text-sm text-gray-400">{groups.length}개</span>
          </div>
          <ul className="divide-y divide-gray-100 max-h-[360px] overflow-y-auto">
            {groups.map((g) => (
              <li
                key={g.groupCode}
                onClick={() => selectGroup(g.groupCode)}
                className={`px-4 py-3 cursor-pointer transition-colors ${
                  selected === g.groupCode ? 'bg-blue-50' : 'hover:bg-gray-50'
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="min-w-0">
                    <p className="font-mono text-sm font-bold text-blue-700">{g.groupCode}</p>
                    <p className="text-sm text-gray-700 truncate">{g.name}</p>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleToggleGroup(g); }}
                      className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                        g.active ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
                      }`}
                    >
                      {g.active ? '활성' : '비활성'}
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleDeleteGroup(g.groupCode); }}
                      className="text-xs text-red-500 hover:text-red-700"
                    >삭제</button>
                  </div>
                </div>
              </li>
            ))}
            {groups.length === 0 && <li className="px-4 py-8 text-center text-gray-400 text-sm">그룹이 없습니다.</li>}
          </ul>
          {/* 그룹 추가 폼 */}
          <form onSubmit={handleCreateGroup} className="p-4 border-t border-gray-100 bg-gray-50 space-y-2">
            <div className="grid grid-cols-2 gap-2">
              <input required value={groupForm.groupCode}
                onChange={(e) => setGroupForm((f) => ({ ...f, groupCode: e.target.value }))}
                placeholder="GROUP_CODE"
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono" />
              <input required value={groupForm.name}
                onChange={(e) => setGroupForm((f) => ({ ...f, name: e.target.value }))}
                placeholder="그룹명"
                className="px-3 py-2 border border-gray-300 rounded-lg text-sm" />
            </div>
            <input value={groupForm.description}
              onChange={(e) => setGroupForm((f) => ({ ...f, description: e.target.value }))}
              placeholder="설명 (선택)"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm" />
            <button type="submit" disabled={savingGroup}
              className="w-full py-2 bg-gray-900 text-white text-sm font-semibold rounded-lg hover:bg-gray-700 disabled:opacity-50">
              {savingGroup ? '추가 중...' : '+ 그룹 추가'}
            </button>
          </form>
        </div>

        {/* 코드 목록 */}
        <div className="xl:col-span-3 bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-900">
              코드 항목 {selected && <span className="font-mono text-blue-700 text-sm">({selected})</span>}
            </h3>
            <span className="text-sm text-gray-400">{codes.length}개</span>
          </div>

          {!selected ? (
            <p className="px-4 py-10 text-center text-gray-400 text-sm">왼쪽에서 그룹을 선택하세요.</p>
          ) : codesLoading ? (
            <div className="py-10 flex justify-center"><Spinner size="md" /></div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    {['코드', '라벨', '정렬', 'extra1', '상태', ''].map((h) => (
                      <th key={h} className="px-4 py-2.5 text-left text-xs font-semibold text-gray-500 uppercase">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {codes.map((c) => (
                    <tr key={c.id} className="hover:bg-gray-50">
                      <td className="px-4 py-2.5 font-mono font-semibold text-gray-800">{c.code}</td>
                      <td className="px-4 py-2.5 text-gray-700">{c.label}</td>
                      <td className="px-4 py-2.5 text-gray-500">{c.sortOrder}</td>
                      <td className="px-4 py-2.5 text-gray-400">{c.extra1 || '-'}</td>
                      <td className="px-4 py-2.5">
                        <button onClick={() => handleToggleCode(c)}
                          className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                            c.active ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
                          }`}>
                          {c.active ? '활성' : '비활성'}
                        </button>
                      </td>
                      <td className="px-4 py-2.5">
                        <button onClick={() => handleDeleteCode(c.id)}
                          className="text-xs text-red-500 hover:text-red-700">삭제</button>
                      </td>
                    </tr>
                  ))}
                  {codes.length === 0 && (
                    <tr><td colSpan={6} className="px-4 py-8 text-center text-gray-400">코드가 없습니다.</td></tr>
                  )}
                </tbody>
              </table>

              {/* 코드 추가 폼 */}
              <form onSubmit={handleCreateCode} className="p-4 border-t border-gray-100 bg-gray-50 grid grid-cols-2 md:grid-cols-5 gap-2 items-end">
                <input required value={codeForm.code}
                  onChange={(e) => setCodeForm((f) => ({ ...f, code: e.target.value }))}
                  placeholder="CODE"
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono" />
                <input required value={codeForm.label}
                  onChange={(e) => setCodeForm((f) => ({ ...f, label: e.target.value }))}
                  placeholder="라벨"
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                <input type="number" value={codeForm.sortOrder}
                  onChange={(e) => setCodeForm((f) => ({ ...f, sortOrder: Number(e.target.value) }))}
                  placeholder="정렬"
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                <input value={codeForm.extra1}
                  onChange={(e) => setCodeForm((f) => ({ ...f, extra1: e.target.value }))}
                  placeholder="extra1 (선택)"
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                <button type="submit" disabled={savingCode}
                  className="py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-50">
                  {savingCode ? '추가 중...' : '+ 코드'}
                </button>
              </form>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default CommonCodeManagementPage;
