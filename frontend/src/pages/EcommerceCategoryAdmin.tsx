import React, { useState, useEffect } from 'react';
import { useToast } from '@/contexts/ToastContext';
import api from '@/api/axios';

interface EcommerceCategory {
  id: number;
  name: string;
  slug: string;
  parentId?: number;
  depth: number;
  sortOrder: number;
  isActive: boolean;
  children: EcommerceCategory[];
}

const EcommerceCategoryAdmin: React.FC = () => {
  const [categories, setCategories] = useState<EcommerceCategory[]>([]);
  const [loading, setLoading] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [selectedParent, setSelectedParent] = useState<number | null>(null);
  const [formData, setFormData] = useState({
    name: '',
    slug: '',
    sortOrder: 0,
  });
  const { showToast } = useToast();

  useEffect(() => {
    loadCategories();
  }, []);

  const loadCategories = async () => {
    setLoading(true);
    try {
      const response = await api.get<EcommerceCategory[]>('/admin/categories');
      setCategories(response.data);
    } catch (error) {
      showToast('카테고리 목록 조회 실패', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post('/admin/categories', {
        name: formData.name,
        slug: formData.slug || undefined,
        parentId: selectedParent,
        sortOrder: formData.sortOrder,
      });
      showToast('카테고리가 생성되었습니다', 'success');
      setShowCreateForm(false);
      setFormData({ name: '', slug: '', sortOrder: 0 });
      setSelectedParent(null);
      loadCategories();
    } catch (error: any) {
      const message = error.response?.data?.message || '카테고리 생성 실패';
      showToast(message, 'error');
    }
  };

  const handleToggleActive = async (id: number, isActive: boolean) => {
    try {
      const endpoint = isActive ? 'deactivate' : 'activate';
      await api.patch(`/admin/categories/${id}/${endpoint}`);
      showToast(`카테고리가 ${isActive ? '비활성화' : '활성화'}되었습니다`, 'success');
      loadCategories();
    } catch (error) {
      showToast('카테고리 상태 변경 실패', 'error');
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('이 카테고리를 삭제하시겠습니까?\n하위 카테고리나 연결된 상품이 있으면 삭제할 수 없습니다.')) {
      return;
    }
    try {
      await api.delete(`/admin/categories/${id}`);
      showToast('카테고리가 삭제되었습니다', 'success');
      loadCategories();
    } catch (error: any) {
      const message = error.response?.data?.message || '카테고리 삭제 실패';
      showToast(message, 'error');
    }
  };

  const renderCategoryTree = (categories: EcommerceCategory[], level = 0) => {
    return categories.map((category) => (
      <div key={category.id} style={{ marginLeft: `${level * 24}px` }}>
        <div className="flex items-center justify-between p-3 border-b hover:bg-gray-50">
          <div className="flex items-center gap-3">
            {level > 0 && <span className="text-gray-400">└─</span>}
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-gray-900">{category.name}</span>
                <span className="text-xs text-gray-500">({category.slug})</span>
                <span className="text-xs bg-gray-200 text-gray-700 px-2 py-0.5 rounded">
                  depth: {category.depth}
                </span>
                <span
                  className={`text-xs px-2 py-0.5 rounded ${
                    category.isActive
                      ? 'bg-green-100 text-green-800'
                      : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {category.isActive ? '활성' : '비활성'}
                </span>
              </div>
              <div className="text-xs text-gray-500 mt-1">
                정렬: {category.sortOrder} | ID: {category.id}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {category.depth < 2 && (
              <button
                onClick={() => {
                  setSelectedParent(category.id);
                  setShowCreateForm(true);
                }}
                className="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
              >
                + 하위 추가
              </button>
            )}
            <button
              onClick={() => handleToggleActive(category.id, category.isActive)}
              className={`px-3 py-1 text-sm rounded ${
                category.isActive
                  ? 'bg-gray-600 hover:bg-gray-700 text-white'
                  : 'bg-green-600 hover:bg-green-700 text-white'
              }`}
            >
              {category.isActive ? '비활성화' : '활성화'}
            </button>
            <button
              onClick={() => handleDelete(category.id)}
              className="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700"
            >
              삭제
            </button>
          </div>
        </div>
        {category.children && category.children.length > 0 && (
          <div>{renderCategoryTree(category.children, level + 1)}</div>
        )}
      </div>
    ));
  };

  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-7xl mx-auto">
        <div className="flex justify-between items-center mb-6">
          <div>
            <h1 className="text-3xl font-bold text-gray-900">이커머스 카테고리 관리</h1>
            <p className="text-sm text-gray-600 mt-1">
              트리 구조 (최대 3단계 depth 0-2)
            </p>
          </div>
          <button
            onClick={() => {
              setShowCreateForm(!showCreateForm);
              setSelectedParent(null);
            }}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 font-medium"
          >
            {showCreateForm ? '취소' : '+ 최상위 카테고리 생성'}
          </button>
        </div>

        {showCreateForm && (
          <div className="bg-white rounded-lg shadow p-6 mb-6">
            <h2 className="text-xl font-semibold text-gray-900 mb-4">
              {selectedParent ? '하위 카테고리 생성' : '최상위 카테고리 생성'}
            </h2>
            {selectedParent && (
              <div className="mb-4 p-3 bg-blue-50 text-blue-900 rounded">
                선택된 부모 카테고리 ID: {selectedParent}
              </div>
            )}
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <label className="block text-sm font-semibold text-gray-900 mb-1">
                  카테고리명 *
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                  placeholder="예: 전자제품"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 text-gray-900"
                />
              </div>
              <div>
                <label className="block text-sm font-semibold text-gray-900 mb-1">
                  Slug (선택사항, 자동 생성)
                </label>
                <input
                  type="text"
                  value={formData.slug}
                  onChange={(e) => setFormData({ ...formData, slug: e.target.value })}
                  placeholder="예: electronics (소문자, 숫자, 하이픈만)"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 text-gray-900"
                />
                <p className="text-xs text-gray-500 mt-1">
                  비워두면 한글 이름으로부터 자동 생성됩니다
                </p>
              </div>
              <div>
                <label className="block text-sm font-semibold text-gray-900 mb-1">
                  정렬 순서
                </label>
                <input
                  type="number"
                  value={formData.sortOrder}
                  onChange={(e) => setFormData({ ...formData, sortOrder: Number(e.target.value) })}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 text-gray-900"
                />
              </div>
              <button
                type="submit"
                className="w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 font-medium"
              >
                생성
              </button>
            </form>
          </div>
        )}

        <div className="bg-white rounded-lg shadow">
          {loading ? (
            <div className="p-8 text-center text-gray-500">로딩 중...</div>
          ) : categories.length === 0 ? (
            <div className="p-8 text-center text-gray-500">
              등록된 카테고리가 없습니다.
            </div>
          ) : (
            <div className="divide-y divide-gray-200">
              {renderCategoryTree(categories)}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default EcommerceCategoryAdmin;
