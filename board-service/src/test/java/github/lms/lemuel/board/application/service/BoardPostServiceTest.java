package github.lms.lemuel.board.application.service;

import github.lms.lemuel.board.application.port.in.BoardPage;
import github.lms.lemuel.board.application.port.in.ManagePostUseCase.PostContentCommand;
import github.lms.lemuel.board.application.port.in.QueryPostUseCase.PostListQuery;
import github.lms.lemuel.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.board.application.port.out.PostSearchCriteria;
import github.lms.lemuel.board.application.port.out.SaveBoardPostPort;
import github.lms.lemuel.board.domain.BoardAccessPolicy;
import github.lms.lemuel.board.domain.BoardActor;
import github.lms.lemuel.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.board.domain.BoardAuthor;
import github.lms.lemuel.board.domain.BoardContentFormat;
import github.lms.lemuel.board.domain.BoardContentPolicy;
import github.lms.lemuel.board.domain.BoardDefinition;
import github.lms.lemuel.board.domain.BoardPost;
import github.lms.lemuel.board.domain.BoardPostStatus;
import github.lms.lemuel.board.domain.BoardSkin;
import github.lms.lemuel.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.board.domain.exception.BoardPostNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardPostServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-15T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private static final BoardActor AUTHOR = BoardActor.of(10L, "USER");
    private static final BoardAuthor AUTHOR_NAME = new BoardAuthor(10L, "au***");
    private static final BoardActor MANAGER = BoardActor.of(99L, "ADMIN");

    @Mock
    private LoadBoardDefinitionPort loadBoardDefinitionPort;
    @Mock
    private LoadBoardPostPort loadBoardPostPort;
    @Mock
    private SaveBoardPostPort saveBoardPostPort;

    private BoardPostService service;

    @BeforeEach
    void setUp() {
        service = new BoardPostService(loadBoardDefinitionPort, loadBoardPostPort, saveBoardPostPort,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static BoardDefinition definition(Long id, List<String> readRoles, boolean active) {
        return BoardDefinition.rehydrate(id, "notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, true, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(readRoles, List.of("USER", "ADMIN"), List.of("USER"), List.of("ADMIN")),
                active, NOW, NOW);
    }

    private static BoardPost post(Long id, Long boardId, boolean secret, BoardPostStatus status) {
        return BoardPost.rehydrate(id, boardId, null, "제목", "본문", BoardContentFormat.TEXT,
                AUTHOR_NAME, false, secret, status, 7L, NOW, NOW);
    }

    private static PostContentCommand command() {
        return new PostContentCommand("제목", "본문", null, false);
    }

    @Test
    @DisplayName("없는 게시판이면 404 로 이어질 예외를 던진다")
    void boardNotFound() {
        when(loadBoardDefinitionPort.findByKey("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("Ghost", AUTHOR, AUTHOR_NAME, command()))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    @DisplayName("닫힌 게시판은 '없는 것'으로 답한다 — 상태를 알려 주지 않는다")
    void inactiveBoardLooksMissing() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), false)));

        assertThatThrownBy(() -> service.list("notice", AUTHOR, new PostListQuery(0, 20, null, null)))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    @DisplayName("읽기 권한이 없으면 게시판 자체가 없는 것으로 답한다 — 쓰기 시도도 마찬가지")
    void unreadableBoardLooksMissing() {
        when(loadBoardDefinitionPort.findByKey("notice"))
                .thenReturn(Optional.of(definition(1L, List.of("ADMIN"), true)));

        assertThatThrownBy(() -> service.create("notice", AUTHOR, AUTHOR_NAME, command()))
                .isInstanceOf(BoardNotFoundException.class);
        verify(saveBoardPostPort, never()).save(any());
    }

    @Test
    @DisplayName("작성은 도메인에 위임하고 저장한다")
    void create() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(saveBoardPostPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardPost created = service.create("  NOTICE ", AUTHOR, AUTHOR_NAME, command());

        assertThat(created.getBoardId()).isEqualTo(1L);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        verify(loadBoardDefinitionPort).findByKey("notice");
    }

    @Test
    @DisplayName("다른 게시판의 글 식별자를 넣으면 404 — 게시판↔글 소속을 대조한다")
    void postFromAnotherBoard() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 2L, false, BoardPostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.read("notice", 5L, AUTHOR))
                .isInstanceOf(BoardPostNotFoundException.class);
        assertThatThrownBy(() -> service.delete("notice", 5L, AUTHOR))
                .isInstanceOf(BoardPostNotFoundException.class);
    }

    @Test
    @DisplayName("볼 수 없는 글은 403 이 아니라 404 다")
    void invisiblePostIsNotFound() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, true, BoardPostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.read("notice", 5L, BoardActor.of(11L, "USER")))
                .isInstanceOf(BoardPostNotFoundException.class);
        verify(saveBoardPostPort, never()).save(any());
    }

    @Test
    @DisplayName("상세 조회는 조회수를 올려 저장한다")
    void readIncreasesViewCount() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(saveBoardPostPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardPost read = service.read("notice", 5L, BoardActor.anonymous());

        assertThat(read.getViewCount()).isEqualTo(8L);
        verify(saveBoardPostPort).save(any(BoardPost.class));
    }

    @Test
    @DisplayName("일반 사용자 목록 조건 — 숨김·남의 비밀글은 질의에서 빠지고 본인 식별자만 실린다")
    void listCriteriaForNormalUser() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.search(any(), anyInt(), anyInt()))
                .thenReturn(BoardPage.of(List.of(), 0, 20, 0));

        service.list("notice", AUTHOR, new PostListQuery(0, 20, " urgent ", "  "));

        ArgumentCaptor<PostSearchCriteria> captor = ArgumentCaptor.forClass(PostSearchCriteria.class);
        verify(loadBoardPostPort).search(captor.capture(), anyInt(), anyInt());
        PostSearchCriteria criteria = captor.getValue();

        assertThat(criteria.boardId()).isEqualTo(1L);
        assertThat(criteria.includeHidden()).isFalse();
        assertThat(criteria.includeAllSecret()).isFalse();
        assertThat(criteria.viewerId()).isEqualTo(10L);
        assertThat(criteria.categoryCode()).isEqualTo("URGENT");
        assertThat(criteria.keyword()).isNull();
    }

    @Test
    @DisplayName("운영 역할 목록 조건 — 숨김과 남의 비밀글까지 포함한다")
    void listCriteriaForManager() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.search(any(), anyInt(), anyInt()))
                .thenReturn(BoardPage.of(List.of(), 0, 20, 0));

        service.list("notice", MANAGER, new PostListQuery(0, 20, null, "검색어"));

        ArgumentCaptor<PostSearchCriteria> captor = ArgumentCaptor.forClass(PostSearchCriteria.class);
        verify(loadBoardPostPort).search(captor.capture(), anyInt(), anyInt());

        assertThat(captor.getValue().includeHidden()).isTrue();
        assertThat(captor.getValue().includeAllSecret()).isTrue();
        assertThat(captor.getValue().keyword()).isEqualTo("검색어");
    }

    @Test
    @DisplayName("페이지 크기는 상한이 있다 — 한 방에 게시판 전체를 덤프할 수 없다")
    void pageSizeIsCapped() {
        PostListQuery query = new PostListQuery(-3, 100_000, null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(100);
        assertThat(new PostListQuery(0, 0, null, null).size()).isEqualTo(20);
    }

    @Test
    @DisplayName("수정·삭제·고정·숨김·복구는 모두 도메인 판정을 거쳐 저장된다")
    void mutationsDelegateToDomain() {
        when(loadBoardDefinitionPort.findByKey("notice")).thenReturn(Optional.of(definition(1L, List.of(), true)));
        when(loadBoardPostPort.findById(5L))
                .thenAnswer(invocation -> Optional.of(post(5L, 1L, false, BoardPostStatus.PUBLISHED)));
        when(saveBoardPostPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.edit("notice", 5L, AUTHOR, new PostContentCommand("새 제목", "새 본문", null, false))
                .getTitle()).isEqualTo("새 제목");
        assertThat(service.changePinned("notice", 5L, MANAGER, true).isPinned()).isTrue();
        assertThat(service.hide("notice", 5L, MANAGER).getStatus()).isEqualTo(BoardPostStatus.HIDDEN);
        assertThat(service.restore("notice", 5L, MANAGER).getStatus()).isEqualTo(BoardPostStatus.PUBLISHED);

        service.delete("notice", 5L, AUTHOR);
        verify(saveBoardPostPort, org.mockito.Mockito.times(5)).save(any());
    }
}
