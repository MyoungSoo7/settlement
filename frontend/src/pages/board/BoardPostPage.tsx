import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  boardApi, boardCommentApi, boardPostApi,
  BoardComment, BoardDefinition, BoardPost,
} from '@/api/board';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 게시글 상세 + 댓글.
 *
 * <p>버튼 노출은 서버가 내려준 `editable`·`deletable` 힌트로 정하지만, 그것은 어디까지나
 * 화면 편의다 — 실제 인가는 매 조작마다 서버(도메인)가 다시 판정한다.
 */
const BoardPostPage: React.FC = () => {
  const { boardKey = '', postId = '' } = useParams();
  const navigate = useNavigate();
  const id = Number(postId);

  const [definition, setDefinition] = useState<BoardDefinition | null>(null);
  const [post, setPost] = useState<BoardPost | null>(null);
  const [comments, setComments] = useState<BoardComment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({ title: '', content: '', secret: false });
  const [commentText, setCommentText] = useState('');
  const [replyTo, setReplyTo] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [def, detail] = await Promise.all([boardApi.get(boardKey), boardPostApi.read(boardKey, id)]);
      setDefinition(def);
      setPost(detail);
      setForm({ title: detail.title, content: detail.content ?? '', secret: detail.secret });
      // 댓글이 꺼진 게시판이면 호출 자체를 하지 않는다 — 서버는 어차피 빈 목록을 주지만
      // 없는 기능을 위해 왕복을 늘릴 이유가 없다.
      setComments(def.content.commentsEnabled ? await boardCommentApi.list(boardKey, id) : []);
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, '글을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [boardKey, id]);

  useEffect(() => {
    void load();
  }, [load]);

  const saveEdit = async () => {
    setBusy(true);
    try {
      await boardPostApi.update(boardKey, id, {
        title: form.title, content: form.content, secret: form.secret,
      });
      setEditing(false);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '수정하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const removePost = async () => {
    if (!window.confirm('이 글을 삭제할까요?')) return;
    try {
      await boardPostApi.remove(boardKey, id);
      navigate(`/boards/${boardKey}`);
    } catch (e) {
      setError(apiErrorMessage(e, '삭제하지 못했습니다.'));
    }
  };

  const togglePin = async () => {
    if (!post) return;
    try {
      await boardPostApi.pin(boardKey, id, !post.pinned);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '고정 상태를 바꾸지 못했습니다.'));
    }
  };

  const submitComment = async () => {
    if (!commentText.trim()) return;
    setBusy(true);
    try {
      await boardCommentApi.create(boardKey, id, commentText, replyTo);
      setCommentText('');
      setReplyTo(null);
      setComments(await boardCommentApi.list(boardKey, id));
    } catch (e) {
      setError(apiErrorMessage(e, '댓글을 등록하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const removeComment = async (commentId: number) => {
    try {
      await boardCommentApi.remove(boardKey, commentId);
      setComments(await boardCommentApi.list(boardKey, id));
    } catch (e) {
      setError(apiErrorMessage(e, '댓글을 삭제하지 못했습니다.'));
    }
  };

  if (loading) {
    return <div className="py-20 flex justify-center"><Spinner size="lg" message="글 로드 중..." /></div>;
  }
  if (!post) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-20 text-center space-y-4">
        <p className="text-gray-500">{error ?? '글을 찾을 수 없습니다.'}</p>
        <button onClick={() => navigate(`/boards/${boardKey}`)} className="text-blue-600 text-sm">
          목록으로
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-3xl mx-auto px-4 py-8 space-y-5">
      <button onClick={() => navigate(`/boards/${boardKey}`)} className="text-sm text-gray-500 hover:text-gray-700">
        ← {definition?.name ?? '목록'}
      </button>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      <article className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
        {editing ? (
          <div className="space-y-3">
            <input
              value={form.title}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            />
            <textarea
              value={form.content}
              onChange={(e) => setForm({ ...form, content: e.target.value })}
              rows={12}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm"
            />
            <div className="flex gap-2">
              <button
                onClick={saveEdit}
                disabled={busy}
                className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold disabled:opacity-50"
              >
                저장
              </button>
              <button
                onClick={() => setEditing(false)}
                className="px-4 py-2 rounded-lg border border-gray-300 text-sm"
              >
                취소
              </button>
            </div>
          </div>
        ) : (
          <>
            <header className="space-y-2 border-b border-gray-100 pb-3">
              <div className="flex items-center gap-2">
                {post.pinned && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-rose-100 text-rose-700">공지</span>
                )}
                {post.secret && <span className="text-gray-400" title="비밀글">🔒</span>}
                <h1 className="text-xl font-bold text-gray-900">{post.title}</h1>
              </div>
              <div className="text-xs text-gray-500 flex gap-3">
                <span>{post.authorName}</span>
                <span>{new Date(post.createdAt).toLocaleString()}</span>
                <span>조회 {post.viewCount}</span>
              </div>
            </header>

            <div className="whitespace-pre-wrap text-gray-800 leading-relaxed">{post.content}</div>

            {post.editable && (
              <div className="flex gap-2 pt-2 border-t border-gray-100 text-sm">
                <button onClick={() => setEditing(true)} className="px-3 py-1.5 rounded border border-gray-300">
                  수정
                </button>
                <button onClick={removePost} className="px-3 py-1.5 rounded text-red-600 hover:bg-red-50">
                  삭제
                </button>
                <button onClick={togglePin} className="px-3 py-1.5 rounded border border-gray-300">
                  {post.pinned ? '고정 해제' : '상단 고정'}
                </button>
              </div>
            )}
          </>
        )}
      </article>

      {definition?.content.commentsEnabled && (
        <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
          <h2 className="font-bold text-gray-900">댓글 {comments.length}</h2>

          <ul className="space-y-3">
            {comments.map((comment) => (
              <li
                key={comment.id}
                className={`text-sm ${comment.parentId ? 'pl-6 border-l-2 border-gray-100' : ''}`}
              >
                <div className="flex items-center gap-2 text-xs text-gray-500">
                  <span className="font-semibold text-gray-700">{comment.authorName}</span>
                  <span>{new Date(comment.createdAt).toLocaleString()}</span>
                  {comment.deletable && (
                    <button onClick={() => removeComment(comment.id)} className="text-red-500 hover:text-red-700">
                      삭제
                    </button>
                  )}
                  {!comment.parentId && comment.status === 'PUBLISHED' && (
                    <button onClick={() => setReplyTo(comment.id)} className="text-blue-500 hover:text-blue-700">
                      답글
                    </button>
                  )}
                </div>
                <p className={`mt-1 ${comment.status === 'DELETED' ? 'text-gray-400 italic' : 'text-gray-800'}`}>
                  {comment.content}
                </p>
              </li>
            ))}
            {comments.length === 0 && <li className="text-sm text-gray-400">첫 댓글을 남겨 보세요.</li>}
          </ul>

          <div className="space-y-2">
            {replyTo && (
              <div className="text-xs text-gray-500 flex items-center gap-2">
                답글 작성 중
                <button onClick={() => setReplyTo(null)} className="text-gray-400 hover:text-gray-600">취소</button>
              </div>
            )}
            <textarea
              value={commentText}
              onChange={(e) => setCommentText(e.target.value)}
              rows={3}
              placeholder="댓글을 입력하세요"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
            <button
              onClick={submitComment}
              disabled={busy}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold disabled:opacity-50"
            >
              등록
            </button>
          </div>
        </section>
      )}
    </div>
  );
};

export default BoardPostPage;
