import api from './axios';

/**
 * 게시판(board-service) API.
 *
 * <p>메타 주도 게시판 — 정의 1행이 게시판 1개이고, 프론트는 단일 라우트가 정의를 읽어 스킨을
 * 바꿔 그린다. 그래서 게시판을 늘리는 데 배포가 필요 없다.
 *
 * <p><b>메뉴 등록은 여기 없다.</b> 메뉴는 order-service 소유라 board-service 가 쓰지 않는다 —
 * 관리 화면이 게시판 생성 후 `@/api/system` 의 `menuApi.create` 를 한 번 더 호출한다
 * (docs/plan/board-service.md §6). 게시판 생성이 곧 전사 네비게이션 변경이 되지 않도록
 * 두 조작을 일부러 갈라 둔 것이다.
 */

export type BoardSkin = 'LIST' | 'GALLERY' | 'FAQ' | 'QNA';
export type BoardContentFormat = 'TEXT' | 'MARKDOWN' | 'HTML';

export const BOARD_SKINS: BoardSkin[] = ['LIST', 'GALLERY', 'FAQ', 'QNA'];
export const BOARD_CONTENT_FORMATS: BoardContentFormat[] = ['TEXT', 'MARKDOWN', 'HTML'];

export const BOARD_SKIN_LABEL: Record<BoardSkin, string> = {
  LIST: '목록형 (공지·자료실)',
  GALLERY: '갤러리형 (이미지 게시판)',
  FAQ: 'FAQ (아코디언)',
  QNA: '문의 (질문·답변)',
};

/** 스킨이 첨부를 전제하는가 — 서버 도메인 불변식의 사본. 폼에서 미리 막아 400 을 덜 보게 한다. */
export const skinRequiresAttachments = (skin: BoardSkin): boolean => skin === 'GALLERY';
/** 스킨이 댓글을 전제하는가 — 위와 같은 이유. 최종 판정은 언제나 서버가 한다. */
export const skinRequiresComments = (skin: BoardSkin): boolean => skin === 'QNA';

export interface BoardContentPayload {
  contentFormat: BoardContentFormat;
  commentsEnabled: boolean;
  secretEnabled: boolean;
  categoryGroupCode?: string | null;
}

export interface BoardAttachmentPayload {
  enabled: boolean;
  maxCount: number;
  maxSizeKb: number;
  allowedExtensions: string[];
}

export interface BoardAccessPayload {
  readRoles: string[];
  writeRoles: string[];
  commentRoles: string[];
  manageRoles: string[];
}

export interface BoardDefinition {
  id: number;
  boardKey: string;
  name: string;
  description?: string | null;
  skin: BoardSkin;
  /** 서버가 계산해 준 착지 경로(`/boards/{key}`). 메뉴 등록이 이 값을 그대로 쓴다. */
  path: string;
  content: BoardContentPayload;
  attachment: BoardAttachmentPayload;
  access: BoardAccessPayload & { publicRead: boolean };
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface BoardCreateRequest {
  boardKey: string;
  name: string;
  description?: string;
  skin: BoardSkin;
  content: BoardContentPayload;
  attachment: BoardAttachmentPayload;
  access: BoardAccessPayload;
}

export type BoardUpdateRequest = Omit<BoardCreateRequest, 'boardKey'>;

export const boardAdminApi = {
  /** GET /admin/boards — 비활성 포함 전체 */
  list: async (): Promise<BoardDefinition[]> =>
    (await api.get<BoardDefinition[]>('/admin/boards')).data,

  create: async (body: BoardCreateRequest): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>('/admin/boards', body)).data,

  update: async (id: number, body: BoardUpdateRequest): Promise<BoardDefinition> =>
    (await api.put<BoardDefinition>(`/admin/boards/${id}`, body)).data,

  deactivate: async (id: number): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>(`/admin/boards/${id}/deactivate`)).data,

  activate: async (id: number): Promise<BoardDefinition> =>
    (await api.post<BoardDefinition>(`/admin/boards/${id}/activate`)).data,

  remove: async (id: number): Promise<void> => {
    await api.delete(`/admin/boards/${id}`);
  },
};

export const boardApi = {
  /** GET /api/boards — 활성 + 내가 읽을 수 있는 게시판만 */
  listVisible: async (): Promise<BoardDefinition[]> =>
    (await api.get<BoardDefinition[]>('/api/boards')).data,

  get: async (boardKey: string): Promise<BoardDefinition> =>
    (await api.get<BoardDefinition>(`/api/boards/${boardKey}`)).data,
};
