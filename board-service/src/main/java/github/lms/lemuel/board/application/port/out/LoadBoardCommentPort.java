package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardComment;

import java.util.List;
import java.util.Optional;

public interface LoadBoardCommentPort {

    Optional<BoardComment> findById(Long id);

    /** 작성순. 답글은 부모 바로 아래 놓이도록 응용 계층이 다시 엮는다. */
    List<BoardComment> findByPostId(Long postId);
}
