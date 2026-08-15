import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  boardApi, boardPostApi, BoardDefinition, BoardPost, BoardPageResponse,
} from '@/api/board';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 게시판 목록 — **단일 라우트가 모든 게시판을 그린다**.
 *
 * <p>`/boards/:boardKey` 하나가 정의를 읽어 스킨에 따라 렌더를 바꾼다. 게시판을 새로 만들어도
 * 라우트도 배포도 늘지 않는 이유가 이것이다.
 *
 * <p>Phase 2 는 LIST 스킨만 구현한다. GALLERY/FAQ/QNA 는 첨부(Phase 3) 이후에 붙이고,
 * 그때까지는 목록형으로 떨어뜨린다 — 렌더되지 않는 화면보다 낫다.
 */
const BoardPage: React.FC = () => {
  const { boardKey = '' } = useParams();
  const navigate = useNavigate();

  const [definition, setDefinition] = useState<BoardDefinition | null>(null);
  const [pageData, setPageData] = useState<BoardPageResponse<BoardPost> | null>(null);
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [writing, setWriting] = useState(false);
  const [form, setForm] = useState({ title: '', content: '', secret: false });
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [def, posts] = await Promise.all([
        boardApi.get(boardKey),
        boardPostApi.list(boardKey, { page, size: 20, keyword: search || undefined }),
      ]);
      setDefinition(def);
      setPageData(posts);
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, '게시판을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [boardKey, page, search]);

  useEffect(() => {
    void load();
  }, [load]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    try {
      await boardPostApi.create(boardKey, {
        title: form.title,
        content: form.content,
        secret: form.secret,
      });
      setForm({ title: '', content: '', secret: false });
      setWriting(false);
      setPage(0);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '글을 등록하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  if (loading && !definition) {
    return <div className="py-20 flex justify-center"><Spinner size="lg" message="게시판 로드 중..." /></div>;
  }

  if (error && !definition) {
    return <p className="py-20 text-center text-gray-500">{error}</p>;
  }

  const posts = pageData?.content ?? [];

  return (
    <div className="max-w-5xl mx-auto px-4 py-8 space-y-5">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{definition?.name}</h1>
          {definition?.description && (
            <p className="text-sm text-gray-500 mt-1">{definition.description}</p>
          )}
        </div>
        <button
          onClick={() => setWriting((prev) => !prev)}
          className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700"
        >
          {writing ? '닫기' : '글쓰기'}
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {writing && (
        <form onSubmit={submit} className="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
          <input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder="제목"
            className="w-full px-3 py-2 border border-gray-300 rounded-lg"
          />
          <textarea
            value={form.content}
            onChange={(e) => setForm({ ...form, content: e.target.value })}
            placeholder="내용"
            rows={8}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm"
          />
          <div className="flex items-center justify-between">
            {definition?.content.secretEnabled ? (
              <label className="flex items-center gap-2 text-sm text-gray-600">
                <input
                  type="checkbox"
                  checked={form.secret}
                  onChange={(e) => setForm({ ...form, secret: e.target.checked })}
                />
                비밀글
              </label>
            ) : <span />}
            <button
              type="submit"
              disabled={saving}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? '등록 중...' : '등록'}
            </button>
          </div>
        </form>
      )}

      <div className="flex gap-2">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); setSearch(keyword); } }}
          placeholder="제목 · 내용 검색"
          className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm"
        />
        <button
          onClick={() => { setPage(0); setSearch(keyword); }}
          className="px-4 py-2 rounded-lg border border-gray-300 text-sm hover:bg-gray-50"
        >
          검색
        </button>
      </div>

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <ul className="divide-y divide-gray-100">
          {posts.map((post) => (
            <li
              key={post.id}
              onClick={() => navigate(`/boards/${boardKey}/${post.id}`)}
              className="px-4 py-3 cursor-pointer hover:bg-gray-50"
            >
              <div className="flex items-center gap-2">
                {post.pinned && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-rose-100 text-rose-700">공지</span>
                )}
                {post.secret && <span className="text-gray-400" title="비밀글">🔒</span>}
                {post.status === 'HIDDEN' && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-gray-200 text-gray-600">숨김</span>
                )}
                <span className="font-medium text-gray-900 truncate">{post.title}</span>
              </div>
              <div className="mt-1 text-xs text-gray-500 flex gap-3">
                <span>{post.authorName}</span>
                <span>{new Date(post.createdAt).toLocaleDateString()}</span>
                <span>조회 {post.viewCount}</span>
              </div>
            </li>
          ))}
          {posts.length === 0 && (
            <li className="px-4 py-12 text-center text-gray-400 text-sm">
              {search ? '검색 결과가 없습니다.' : '아직 글이 없습니다.'}
            </li>
          )}
        </ul>
      </div>

      {pageData && pageData.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 text-sm">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40"
          >
            이전
          </button>
          <span className="text-gray-500">{page + 1} / {pageData.totalPages}</span>
          <button
            disabled={page + 1 >= pageData.totalPages}
            onClick={() => setPage((p) => p + 1)}
            className="px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40"
          >
            다음
          </button>
        </div>
      )}
    </div>
  );
};

export default BoardPage;
