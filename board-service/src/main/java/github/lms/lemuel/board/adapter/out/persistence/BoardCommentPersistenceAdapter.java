package github.lms.lemuel.board.adapter.out.persistence;

import github.lms.lemuel.board.application.port.out.LoadBoardCommentPort;
import github.lms.lemuel.board.application.port.out.SaveBoardCommentPort;
import github.lms.lemuel.board.domain.BoardComment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BoardCommentPersistenceAdapter implements LoadBoardCommentPort, SaveBoardCommentPort {

    private final SpringDataBoardCommentRepository repository;

    @Override
    public Optional<BoardComment> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(BoardCommentJpaEntity::toDomain);
    }

    @Override
    public List<BoardComment> findByPostId(Long postId) {
        return repository.findAllByPostIdOrderByIdAsc(postId).stream()
                .map(BoardCommentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public BoardComment save(BoardComment comment) {
        BoardCommentJpaEntity entity = comment.getId() == null
                ? BoardCommentJpaEntity.from(comment)
                : repository.findById(comment.getId())
                .map(existing -> {
                    existing.apply(comment);
                    return existing;
                })
                .orElseGet(() -> BoardCommentJpaEntity.from(comment));

        return repository.save(entity).toDomain();
    }
}
