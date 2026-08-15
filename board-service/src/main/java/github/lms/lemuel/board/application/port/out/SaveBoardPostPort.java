package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardPost;

public interface SaveBoardPostPort {

    BoardPost save(BoardPost post);
}
