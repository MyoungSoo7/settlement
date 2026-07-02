import React, { useEffect, useMemo, useState } from 'react';
import { rbacApi, Role, Permission } from '@/api/system';
import Spinner from '@/components/Spinner';

const RbacManagementPage: React.FC = () => {
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedRole, setSelectedRole] = useState<Role | null>(null);
  const [draft, setDraft] = useState<Set<number>>(new Set());
  const [saving, setSaving] = useState(false);
  const [savedMsg, setSavedMsg] = useState<string | null>(null);

  const load = async () => {
    try {
      const [r, p] = await Promise.all([rbacApi.getRoles(), rbacApi.getPermissions()]);
      setRoles(r);
      setPermissions(p);
      if (r.length) pickRole(r[0]);
    } catch {
      setError('RBAC 데이터를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const pickRole = (role: Role) => {
    setSelectedRole(role);
    setDraft(new Set(role.permissionIds));
    setSavedMsg(null);
  };

  // 카테고리별 그룹핑
  const grouped = useMemo(() => {
    const map = new Map<string, Permission[]>();
    permissions.forEach((p) => {
      if (!map.has(p.category)) map.set(p.category, []);
      map.get(p.category)!.push(p);
    });
    return Array.from(map.entries());
  }, [permissions]);

  const toggle = (id: number) => {
    setDraft((d) => {
      const next = new Set(d);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
    setSavedMsg(null);
  };

  const toggleCategory = (perms: Permission[], allOn: boolean) => {
    setDraft((d) => {
      const next = new Set(d);
      perms.forEach((p) => (allOn ? next.delete(p.id) : next.add(p.id)));
      return next;
    });
    setSavedMsg(null);
  };

  const dirty = useMemo(() => {
    if (!selectedRole) return false;
    const orig = new Set(selectedRole.permissionIds);
    if (orig.size !== draft.size) return true;
    for (const id of draft) if (!orig.has(id)) return true;
    return false;
  }, [draft, selectedRole]);

  const handleSave = async () => {
    if (!selectedRole) return;
    setSaving(true);
    try {
      const updated = await rbacApi.updateRolePermissions(selectedRole.id, Array.from(draft));
      setRoles((list) => list.map((r) => (r.id === updated.id ? updated : r)));
      setSelectedRole(updated);
      setDraft(new Set(updated.permissionIds));
      setSavedMsg('권한이 저장되었습니다.');
    } catch (err: any) {
      alert(err.response?.data?.message || '저장 실패');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="py-20 flex justify-center"><Spinner size="lg" message="RBAC 로드 중..." /></div>;
  if (error)   return <p className="text-red-600 py-10 text-center">{error}</p>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">RBAC 관리</h1>
        <p className="text-sm text-gray-500 mt-1">역할(Role)별로 권한(Permission)을 부여합니다. 기존 로그인 역할 체계 위에 추가되는 권한 레이어입니다.</p>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-4 gap-6">
        {/* 역할 목록 */}
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden h-fit">
          <div className="px-4 py-3 border-b border-gray-100">
            <h3 className="font-bold text-gray-900">역할</h3>
          </div>
          <ul className="divide-y divide-gray-100">
            {roles.map((role) => (
              <li key={role.id}>
                <button
                  onClick={() => pickRole(role)}
                  className={`w-full text-left px-4 py-3 transition-colors ${
                    selectedRole?.id === role.id ? 'bg-blue-50' : 'hover:bg-gray-50'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono font-bold text-sm text-gray-900">{role.code}</span>
                    {role.builtin && (
                      <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-500">기본</span>
                    )}
                  </div>
                  <p className="text-xs text-gray-500 mt-0.5">{role.name}</p>
                  <p className="text-xs text-blue-600 mt-1">{role.permissionIds.length}개 권한</p>
                </button>
              </li>
            ))}
          </ul>
        </div>

        {/* 권한 매트릭스 */}
        <div className="xl:col-span-3 bg-white rounded-xl border border-gray-200 overflow-hidden">
          {!selectedRole ? (
            <p className="px-4 py-10 text-center text-gray-400">왼쪽에서 역할을 선택하세요.</p>
          ) : (
            <>
              <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between sticky top-0 bg-white z-10">
                <div>
                  <h3 className="font-bold text-gray-900">
                    <span className="font-mono text-blue-700">{selectedRole.code}</span> 권한 설정
                  </h3>
                  <p className="text-xs text-gray-500 mt-0.5">
                    선택 {draft.size} / 전체 {permissions.length}
                    {dirty && <span className="text-orange-500 font-semibold ml-2">· 변경됨</span>}
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  {savedMsg && <span className="text-sm text-green-600 font-medium">{savedMsg}</span>}
                  <button
                    onClick={handleSave}
                    disabled={!dirty || saving}
                    className="px-5 py-2 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {saving ? '저장 중...' : '권한 저장'}
                  </button>
                </div>
              </div>

              <div className="p-5 space-y-5">
                {grouped.map(([category, perms]) => {
                  const onCount = perms.filter((p) => draft.has(p.id)).length;
                  const allOn = onCount === perms.length;
                  return (
                    <div key={category} className="border border-gray-100 rounded-lg overflow-hidden">
                      <div className="px-4 py-2.5 bg-gray-50 border-b border-gray-100 flex items-center justify-between">
                        <span className="text-sm font-bold text-gray-700">
                          {category} <span className="text-gray-400 font-normal">({onCount}/{perms.length})</span>
                        </span>
                        <button
                          onClick={() => toggleCategory(perms, allOn)}
                          className="text-xs font-semibold text-blue-600 hover:text-blue-800"
                        >
                          {allOn ? '전체 해제' : '전체 선택'}
                        </button>
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-px bg-gray-100">
                        {perms.map((p) => {
                          const on = draft.has(p.id);
                          return (
                            <label
                              key={p.id}
                              className={`flex items-start gap-2.5 px-4 py-3 cursor-pointer bg-white transition-colors ${
                                on ? 'bg-blue-50' : 'hover:bg-gray-50'
                              }`}
                            >
                              <input
                                type="checkbox"
                                checked={on}
                                onChange={() => toggle(p.id)}
                                className="mt-0.5 w-4 h-4 accent-blue-600"
                              />
                              <span className="min-w-0">
                                <span className="block text-sm font-medium text-gray-800">{p.name}</span>
                                <span className="block text-[11px] font-mono text-gray-400 truncate">{p.code}</span>
                              </span>
                            </label>
                          );
                        })}
                      </div>
                    </div>
                  );
                })}
                {grouped.length === 0 && <p className="text-center text-gray-400 py-8">등록된 권한이 없습니다.</p>}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default RbacManagementPage;
