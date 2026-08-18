package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;

/**
 * 포인트 원장 엔트리 저장 포트 (append-only).
 *
 * <p>{@link #nextSequence} 는 같은 {@code (type, referenceType, referenceId)} 가 정당하게 반복될 때
 * L3 자연키를 비켜 가기 위한 다음 번호를 준다 — 같은 tender 를 여러 번 부분 환불하는 경우가 그렇다.
 */
public interface PointEntryPort {

    PointEntry append(PointEntry entry);

    int nextSequence(Long accountId, PointEntryType type, String referenceType, String referenceId);

    /**
     * 같은 참조의 엔트리 전부(sequence 오름차순). 환불 복원이 "원래 어느 로트를 얼마나 썼는지"와
     * "이미 얼마를 되돌렸는지"를 알아내는 유일한 근거다.
     */
    java.util.List<PointEntry> loadByReference(Long accountId, PointEntryType type,
                                               String referenceType, String referenceId);

    /**
     * 동일 자연키의 엔트리가 이미 있는가 — 멱등 단축 반환용(L3 UNIQUE 위반을 예외로 받기 전에
     * 정상 경로에서 걸러 낸다).
     */
    boolean exists(Long accountId, PointEntryType type, String referenceType, String referenceId, int sequence);
}
