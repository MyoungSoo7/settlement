package github.lms.lemuel.board.adapter.out.persistence;

import github.lms.lemuel.board.domain.BoardAttachmentKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataBoardAttachmentRepository extends JpaRepository<BoardAttachmentJpaEntity, Long> {

    List<BoardAttachmentJpaEntity> findAllByPostIdOrderBySortOrderAscIdAsc(Long postId);

    int countByPostId(Long postId);

    /** 목록 화면의 대표 이미지용 — 여러 글의 이미지를 한 번에 가져와 어댑터가 글별 첫 장으로 접는다. */
    List<BoardAttachmentJpaEntity> findAllByPostIdInAndKindOrderBySortOrderAscIdAsc(
            Collection<Long> postIds, BoardAttachmentKind kind);
}
