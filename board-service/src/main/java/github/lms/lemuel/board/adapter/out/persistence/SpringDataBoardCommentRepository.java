package github.lms.lemuel.board.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataBoardCommentRepository extends JpaRepository<BoardCommentJpaEntity, Long> {

    List<BoardCommentJpaEntity> findAllByPostIdOrderByIdAsc(Long postId);
}
