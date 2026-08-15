package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardAttachment;

public interface SaveBoardAttachmentPort {

    BoardAttachment save(BoardAttachment attachment);

    void delete(Long id);
}
