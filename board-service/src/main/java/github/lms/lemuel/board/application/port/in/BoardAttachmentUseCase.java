package github.lms.lemuel.board.application.port.in;

import github.lms.lemuel.board.domain.BoardActor;
import github.lms.lemuel.board.domain.BoardAttachment;

import java.util.List;
import java.util.Map;

/**
 * 첨부 유스케이스.
 *
 * <p>업로드는 글이 만들어진 <b>뒤</b>에 붙인다. 그래서 "GALLERY 스킨은 대표 이미지가 있어야 한다"를
 * 글 생성 시점에 강제할 수 없다 — 그 불변식은 <b>게시판 정의 시점</b>(GALLERY ⇒ 첨부 필수)으로
 * 옮겨 갔고, 이미지 없는 글은 목록에서 자리표시로 그린다(설계문서 §14).
 */
public interface BoardAttachmentUseCase {

    List<BoardAttachment> listByPost(String boardKey, Long postId, BoardActor actor);

    /**
     * 목록 화면의 대표 이미지 — 글 여러 건을 <b>한 번의 질의</b>로 채운다.
     *
     * <p>글마다 첨부를 따로 부르면 갤러리 한 화면에 20번의 왕복이 생긴다.
     */
    Map<Long, BoardAttachment> firstImageByPost(String boardKey, List<Long> postIds, BoardActor actor);

    BoardAttachment upload(String boardKey, Long postId, BoardActor actor, String originalName, byte[] content);

    void delete(String boardKey, Long attachmentId, BoardActor actor);

    /** 다운로드 — 볼 수 없는 글의 첨부는 404 다(첨부 URL 로 비밀글이 새지 않게). */
    AttachmentDownload download(String boardKey, Long attachmentId, BoardActor actor);

    record AttachmentDownload(BoardAttachment attachment, byte[] content) {
    }
}
