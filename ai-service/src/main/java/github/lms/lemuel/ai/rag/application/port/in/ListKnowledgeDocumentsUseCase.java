package github.lms.lemuel.ai.rag.application.port.in;

import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;

import java.util.List;

/**
 * 적재된 지식 문서 목록 조회 인바운드 포트 (관리자 전용).
 *
 * <p><b>적재 포트와 분리한 이유:</b> {@link IngestKnowledgeUseCase} 는 지식베이스를 바꾸는 명령이고
 * 이것은 상태를 읽기만 하는 조회다. 둘을 한 인터페이스에 두면 "조회를 하려면 적재 유스케이스에
 * 의존해야 하는" 구조가 되어, 읽기만 필요한 호출자에게 쓰기 능력이 딸려 간다.
 * (구현은 같은 서비스가 겸해도 된다 — {@code KnowledgeRetrievalService} 가 검색 포트와
 * 챗봇 아웃바운드 포트를 함께 구현하는 것과 같은 선례다.)
 *
 * <p><b>왜 필요한가:</b> 적재·삭제만 있고 목록이 없으면 지식베이스는 <b>쓰기 전용 창고</b>가 된다.
 * 삭제는 {@code sourceUri} 를 알아야 하는데 무엇이 들어 있는지 알 방법이 없으므로, 매니페스트에서
 * 빠지거나 출처가 바뀐 문서는 회수되지 못한 채 영원히 답변 근거로 실린다.
 */
public interface ListKnowledgeDocumentsUseCase {

    /** 적재된 문서 전량 (출처 오름차순). 청크 본문은 포함하지 않는다. */
    List<KnowledgeDocument> listDocuments();
}
