import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  boardAdminApi,
  boardApi,
  BOARD_SKINS,
  BOARD_CONTENT_FORMATS,
  BOARD_SKIN_LABEL,
  skinRequiresAttachments,
  skinRequiresComments,
  type BoardDefinition,
  type BoardCreateRequest,
} from '@/api/board';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const notice: BoardDefinition = {
  id: 1,
  boardKey: 'notice',
  name: '공지사항',
  description: '전사 공지',
  skin: 'LIST',
  path: '/boards/notice',
  content: { contentFormat: 'TEXT', commentsEnabled: false, secretEnabled: false, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: ['jpg'] },
  access: { readRoles: [], writeRoles: ['ADMIN'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'], publicRead: true },
  active: true,
  createdAt: '2026-08-15T00:00:00',
  updatedAt: '2026-08-15T00:00:00',
};

const createBody: BoardCreateRequest = {
  boardKey: 'qna',
  name: '문의',
  skin: 'QNA',
  content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: true, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: [] },
  access: { readRoles: ['USER'], writeRoles: ['USER'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'] },
};

beforeEach(() => {
  vi.resetAllMocks();
});

describe('board 메타 상수', () => {
  it('스킨·본문형식 후보는 라벨과 짝이 맞는다 — 라벨 누락은 셀렉트에 빈 항목으로 샌다', () => {
    expect(BOARD_SKINS).toEqual(['LIST', 'GALLERY', 'FAQ', 'QNA']);
    expect(BOARD_CONTENT_FORMATS).toEqual(['TEXT', 'MARKDOWN', 'HTML']);
    BOARD_SKINS.forEach((skin) => expect(BOARD_SKIN_LABEL[skin]).toBeTruthy());
  });

  it('GALLERY 만 첨부를 전제하고, QNA 만 댓글을 전제한다 (서버 도메인 불변식의 사본)', () => {
    expect(BOARD_SKINS.filter(skinRequiresAttachments)).toEqual(['GALLERY']);
    expect(BOARD_SKINS.filter(skinRequiresComments)).toEqual(['QNA']);
  });
});

describe('boardAdminApi', () => {
  it('목록은 비활성 포함 관리 경로로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [notice] });

    const result = await boardAdminApi.list();

    expect(api.get).toHaveBeenCalledWith('/admin/boards');
    expect(result).toHaveLength(1);
  });

  it('생성은 boardKey 를 본문에 실어 POST 한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...notice, id: 2, boardKey: 'qna' } });

    const result = await boardAdminApi.create(createBody);

    expect(api.post).toHaveBeenCalledWith('/admin/boards', createBody);
    expect(result.id).toBe(2);
  });

  it('수정은 id 경로로 PUT 한다 (키는 불변이라 본문에 없다)', async () => {
    vi.mocked(api.put).mockResolvedValueOnce({ data: notice });

    await boardAdminApi.update(1, {
      name: '공지', skin: 'LIST', content: createBody.content,
      attachment: createBody.attachment, access: createBody.access,
    });

    expect(api.put).toHaveBeenCalledWith('/admin/boards/1', expect.objectContaining({ name: '공지' }));
  });

  it('닫기/열기는 각각 전용 경로를 쓴다 — 수정으로 상태를 바꾸지 않는다', async () => {
    vi.mocked(api.post)
      .mockResolvedValueOnce({ data: { ...notice, active: false } })
      .mockResolvedValueOnce({ data: notice });

    expect((await boardAdminApi.deactivate(1)).active).toBe(false);
    expect((await boardAdminApi.activate(1)).active).toBe(true);
    expect(api.post).toHaveBeenNthCalledWith(1, '/admin/boards/1/deactivate');
    expect(api.post).toHaveBeenNthCalledWith(2, '/admin/boards/1/activate');
  });

  it('삭제는 본문 없이 DELETE 만 보낸다', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await expect(boardAdminApi.remove(1)).resolves.toBeUndefined();
    expect(api.delete).toHaveBeenCalledWith('/admin/boards/1');
  });
});

describe('boardApi (사용자 경로)', () => {
  it('활성 + 읽기 권한이 있는 게시판만 오는 공개 목록을 부른다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [notice] });

    await boardApi.listVisible();

    expect(api.get).toHaveBeenCalledWith('/api/boards');
  });

  it('단건 조회는 id 가 아니라 boardKey 로 간다 — 링크가 키로 만들어지기 때문이다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: notice });

    const result = await boardApi.get('notice');

    expect(api.get).toHaveBeenCalledWith('/api/boards/notice');
    expect(result.path).toBe('/boards/notice');
  });
});
