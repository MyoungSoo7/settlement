package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardAttachment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadBoardAttachmentPort {

    Optional<BoardAttachment> findById(Long id);

    List<BoardAttachment> findByPostId(Long postId);

    int countByPostId(Long postId);

    /**
     * 글마다 대표 이미지(정렬 첫 번째 IMAGE) 하나씩.
     *
     * <p>갤러리 목록이 글 하나당 한 번씩 첨부를 조회하면 한 화면에 20번의 왕복이 생긴다.
     * 목록은 <b>한 번의 질의</b>로 끝나야 한다.
     */
    Map<Long, BoardAttachment> findFirstImageByPostIds(List<Long> postIds);
}
