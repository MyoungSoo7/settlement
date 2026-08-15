package github.lms.lemuel.board.application.service;

import github.lms.lemuel.board.application.port.out.DetectFileTypePort;
import github.lms.lemuel.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.board.application.port.out.SaveBoardAttachmentPort;
import github.lms.lemuel.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.board.domain.BoardAccessPolicy;
import github.lms.lemuel.board.domain.BoardActor;
import github.lms.lemuel.board.domain.BoardAttachment;
import github.lms.lemuel.board.domain.BoardAttachmentKind;
import github.lms.lemuel.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.board.domain.BoardAuthor;
import github.lms.lemuel.board.domain.BoardContentFormat;
import github.lms.lemuel.board.domain.BoardContentPolicy;
import github.lms.lemuel.board.domain.BoardDefinition;
import github.lms.lemuel.board.domain.BoardPost;
import github.lms.lemuel.board.domain.BoardPostStatus;
import github.lms.lemuel.board.domain.BoardSkin;
import github.lms.lemuel.board.domain.DetectedFileType;
import github.lms.lemuel.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.board.domain.exception.BoardAttachmentNotFoundException;
import github.lms.lemuel.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.board.domain.exception.BoardPostNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardAttachmentServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-15T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private static final BoardActor AUTHOR = BoardActor.of(10L, "USER");
    private static final BoardActor STRANGER = BoardActor.of(11L, "USER");
    private static final BoardAuthor AUTHOR_NAME = new BoardAuthor(10L, "au***");
    private static final DetectedFileType JPEG = DetectedFileType.of("jpg", "image/jpeg", true, "jpeg");
    private static final byte[] CONTENT = new byte[]{1, 2, 3, 4};

    @Mock
    private LoadBoardDefinitionPort loadBoardDefinitionPort;
    @Mock
    private LoadBoardPostPort loadBoardPostPort;
    @Mock
    private LoadBoardAttachmentPort loadBoardAttachmentPort;
    @Mock
    private SaveBoardAttachmentPort saveBoardAttachmentPort;
    @Mock
    private StoreAttachmentPort storeAttachmentPort;
    @Mock
    private DetectFileTypePort detectFileTypePort;

    private BoardAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new BoardAttachmentService(loadBoardDefinitionPort, loadBoardPostPort,
                loadBoardAttachmentPort, saveBoardAttachmentPort, storeAttachmentPort,
                detectFileTypePort, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static BoardDefinition definition(int maxCount) {
        return BoardDefinition.rehydrate(1L, "gallery", "갤러리", null, BoardSkin.GALLERY,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.rehydrate(true, maxCount, 100, List.of("jpg", "png")),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
    }

    private static BoardPost post() {
        return BoardPost.rehydrate(5L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                AUTHOR_NAME, false, false, BoardPostStatus.PUBLISHED, 0L, NOW, NOW);
    }

    private static BoardAttachment attachment(Long boardId) {
        return BoardAttachment.rehydrate(9L, 5L, boardId, BoardAttachmentKind.IMAGE, "photo.jpg",
                "uuid.jpg", "board-1/post-5/uuid.jpg", "image/jpeg", 4, 0, NOW);
    }

    @Test
    @DisplayName("업로드는 판정 → 검증 → 저장 → 행 기록 순으로 흐른다")
    void uploadHappyPath() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        when(storeAttachmentPort.store(1L, 5L, "jpg", CONTENT))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(saveBoardAttachmentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardAttachment saved = service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT);

        assertThat(saved.getKind()).isEqualTo(BoardAttachmentKind.IMAGE);
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getOriginalName()).isEqualTo("photo.jpg");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("검증에 걸리면 디스크에 닿지 않는다 — 거절된 파일이 남지 않게")
    void rejectedUploadNeverTouchesDisk() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        // 확장자만 바꿔 올린 파일
        when(detectFileTypePort.detect(CONTENT))
                .thenReturn(DetectedFileType.of("pdf", "application/pdf", false));

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(BoardInvariantViolationException.class);

        verify(storeAttachmentPort, never()).store(anyLong(), anyLong(), anyString(), any());
        verify(saveBoardAttachmentPort, never()).save(any());
    }

    @Test
    @DisplayName("개수 한도를 넘으면 거절한다 — 이미 붙은 수는 저장소가 알려 준다")
    void countLimit() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(2)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(2);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("최대 2개");

        verify(storeAttachmentPort, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("남의 글에는 첨부할 수 없다 — 첨부 권한은 글 수정 권한을 따른다")
    void strangerCannotAttach() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);

        assertThatThrownBy(() -> service.upload("gallery", 5L, STRANGER, "photo.jpg", CONTENT))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("행 기록이 실패하면 방금 쓴 파일을 되돌린다 — 파일시스템은 트랜잭션 밖이다")
    void compensatesFileOnDbFailure() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        when(storeAttachmentPort.store(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(saveBoardAttachmentPort.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(IllegalStateException.class);

        verify(storeAttachmentPort).delete("board-1/post-5/uuid.jpg");
    }

    @Test
    @DisplayName("볼 수 없는 글의 첨부는 404 — 첨부 URL 로 비밀글이 새지 않게")
    void downloadOfInvisiblePost() {
        BoardPost secret = BoardPost.rehydrate(5L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                AUTHOR_NAME, false, true, BoardPostStatus.PUBLISHED, 0L, NOW, NOW);
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(secret));

        assertThatThrownBy(() -> service.download("gallery", 9L, STRANGER))
                .isInstanceOf(BoardPostNotFoundException.class);
        verify(storeAttachmentPort, never()).read(anyString());
    }

    @Test
    @DisplayName("다른 게시판의 첨부 식별자는 404")
    void attachmentFromAnotherBoard() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(2L)));

        assertThatThrownBy(() -> service.download("gallery", 9L, AUTHOR))
                .isInstanceOf(BoardAttachmentNotFoundException.class);
    }

    @Test
    @DisplayName("다운로드는 저장 경로로 바이트를 읽어 판정된 메타와 함께 돌려준다")
    void download() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(storeAttachmentPort.read("board-1/post-5/uuid.jpg")).thenReturn(CONTENT);

        var download = service.download("gallery", 9L, BoardActor.anonymous());

        assertThat(download.content()).isEqualTo(CONTENT);
        assertThat(download.attachment().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("삭제는 행을 먼저 지우고 파일을 지운다 — 참조가 남는 것보다 파일이 남는 편이 낫다")
    void delete() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));

        service.delete("gallery", 9L, AUTHOR);

        verify(saveBoardAttachmentPort).delete(9L);
        verify(storeAttachmentPort).delete("board-1/post-5/uuid.jpg");
    }

    @Test
    @DisplayName("남의 글 첨부는 지울 수 없다")
    void strangerCannotDelete() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));

        assertThatThrownBy(() -> service.delete("gallery", 9L, STRANGER))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(saveBoardAttachmentPort, never()).delete(anyLong());
    }

    @Test
    @DisplayName("목록은 글 가시성을 먼저 태운다")
    void listByPost() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.findByPostId(5L)).thenReturn(List.of(attachment(1L)));

        assertThat(service.listByPost("gallery", 5L, BoardActor.anonymous())).hasSize(1);
        verify(loadBoardAttachmentPort).findByPostId(eq(5L));
    }
}
