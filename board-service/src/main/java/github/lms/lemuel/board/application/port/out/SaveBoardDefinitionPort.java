package github.lms.lemuel.board.application.port.out;

import github.lms.lemuel.board.domain.BoardDefinition;

public interface SaveBoardDefinitionPort {

    BoardDefinition save(BoardDefinition definition);

    void delete(Long id);
}
