package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record ReputationResponse(String stockCode, LocalDate snapshotDate, int score, String grade,
                                 int articleCount, int positiveCount, int negativeCount, int neutralCount,
                                 Map<String, Integer> negativeByCategory, Instant calculatedAt) {

    public static ReputationResponse from(ReputationScore s) {
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (IssueCategory category : IssueCategory.values()) {
            int count = s.negativeCountOf(category);
            if (count > 0) {
                byCategory.put(category.name(), count);
            }
        }
        return new ReputationResponse(s.stockCode(), s.snapshotDate(), s.score(), s.grade().name(),
                s.articleCount(), s.positiveCount(), s.negativeCount(), s.neutralCount(),
                byCategory, s.calculatedAt());
    }
}
