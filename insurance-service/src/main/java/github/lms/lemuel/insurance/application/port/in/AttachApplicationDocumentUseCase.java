package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.ApplicationDocument;

/**
 * 청약서류 첨부 유스케이스 — 업로드 → OCR 추출 → 청약 자동 대사까지 한 트랜잭션 (ADR 0036 확산).
 *
 * <p>멱등: 같은 (applicationId, 파일 해시) 재업로드는 기존 서류를 그대로 반환한다(OCR 재호출 없음).
 */
public interface AttachApplicationDocumentUseCase {

    ApplicationDocument attach(AttachDocumentCommand command);

    /**
     * @param applicationId 청약 자연키
     * @param uploadedBy    업로더 식별자 — JWT 주체에서 파생({@code FcIdentity})
     * @param fileName      원본 파일명
     * @param contentType   콘텐츠 타입 (image/*, application/pdf)
     * @param content       파일 본문
     */
    record AttachDocumentCommand(String applicationId, String uploadedBy, String fileName,
                                 String contentType, byte[] content) {
    }
}
