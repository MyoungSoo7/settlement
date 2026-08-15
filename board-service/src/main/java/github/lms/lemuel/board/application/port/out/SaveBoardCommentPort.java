package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardComment;

public interface SaveBoardCommentPort {

    BoardComment save(BoardComment comment);
}
