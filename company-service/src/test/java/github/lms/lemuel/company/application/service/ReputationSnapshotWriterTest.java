package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.application.port.out.PublishReputationEventPort;
import github.lms.lemuel.company.application.port.out.SaveReputationPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReputationSnapshotWriterTest {

    private final LoadReputationPort loadReputationPort = mock(LoadReputationPort.class);
    private final SaveReputationPort saveReputationPort = mock(SaveReputationPort.class);
    private final PublishReputationEventPort publishPort = mock(PublishReputationEventPort.class);
    private final ReputationSnapshotWriter writer =
            new ReputationSnapshotWriter(loadReputationPort, saveReputationPort, publishPort);

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 7);
    private static final Instant AT = Instant.parse("2026-07-07T00:00:00Z");

    /** 점수 grade 를 원하는 등급으로 만드는 헬퍼 — 전부 최대가중 부정이면 0점(E), 전부 긍정이면 100점(A). */
    private static ReputationScore scoreWithGrade(ReputationGrade grade) {
        List<ArticleSentiment> sentiments = grade == ReputationGrade.A
                ? List.of(ArticleSentiment.positive())
                : List.of(ArticleSentiment.negative(IssueCategory.FINANCIAL));  // 0점 E
        return ReputationScore.compute("005930", TODAY, sentiments, AT);
    }

    @Test
    @DisplayName("최초 스냅샷(직전 없음)은 저장 후 previousGrade=null 로 발행한다")
    void publishesOnFirstSnapshot() {
        ReputationScore score = scoreWithGrade(ReputationGrade.A);
        when(loadReputationPort.findLatest("005930")).thenReturn(Optional.empty());
        when(saveReputationPort.saveIfAbsent(score)).thenReturn(true);

        assertTrue(writer.writeIfChanged(score));

        verify(publishPort).publishReputationChanged(eq(score), isNull());
    }

    @Test
    @DisplayName("등급이 바뀌면 직전 등급과 함께 발행한다")
    void publishesOnGradeChange() {
        ReputationScore prior = scoreWithGrade(ReputationGrade.A);   // 직전 A
        ReputationScore now = scoreWithGrade(ReputationGrade.E);     // 오늘 E
        when(loadReputationPort.findLatest("005930")).thenReturn(Optional.of(prior));
        when(saveReputationPort.saveIfAbsent(now)).thenReturn(true);

        assertTrue(writer.writeIfChanged(now));

        verify(publishPort).publishReputationChanged(now, ReputationGrade.A);
    }

    @Test
    @DisplayName("등급이 같으면 저장은 하되 발행하지 않는다")
    void noPublishWhenGradeUnchanged() {
        ReputationScore prior = scoreWithGrade(ReputationGrade.E);
        ReputationScore now = scoreWithGrade(ReputationGrade.E);
        when(loadReputationPort.findLatest("005930")).thenReturn(Optional.of(prior));
        when(saveReputationPort.saveIfAbsent(now)).thenReturn(true);

        assertTrue(writer.writeIfChanged(now));

        verify(publishPort, never()).publishReputationChanged(any(), any());
    }

    @Test
    @DisplayName("오늘자 스냅샷이 이미 있어 저장 안 되면 발행도 안 한다")
    void noPublishWhenNotSaved() {
        ReputationScore score = scoreWithGrade(ReputationGrade.E);
        when(loadReputationPort.findLatest("005930")).thenReturn(Optional.empty());
        when(saveReputationPort.saveIfAbsent(score)).thenReturn(false);

        assertFalse(writer.writeIfChanged(score));

        verify(publishPort, never()).publishReputationChanged(any(), any());
    }
}
