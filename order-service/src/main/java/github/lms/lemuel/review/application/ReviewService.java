package github.lms.lemuel.review.application;

import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.domain.Review;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    private final SaveReviewPort saveReviewPort;
    private final LoadReviewPort loadReviewPort;

    /** 리뷰 작성 */
    public Review createReview(Long productId, Long userId, int rating, String content) {
        log.info("리뷰 작성 시작: productId={}, userId={}, rating={}", productId, userId, rating);

        if (loadReviewPort.existsByUserIdAndProductId(userId, productId)) {
            throw new IllegalStateException("이미 해당 상품에 리뷰를 작성하셨습니다.");
        }

        Review review = Review.create(productId, userId, rating, content);
        try {
            Review saved = saveReviewPort.save(review);
            log.info("리뷰 작성 완료: reviewId={}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("이미 해당 상품에 리뷰를 작성하셨습니다.");
        }
    }

    /** 리뷰 수정 */
    public Review updateReview(Long reviewId, Long userId, int rating, String content) {
        log.info("리뷰 수정 시작: reviewId={}, userId={}", reviewId, userId);

        Review review = loadReviewPort.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰를 찾을 수 없습니다. id=" + reviewId));

        if (!review.getUserId().equals(userId)) {
            throw new IllegalStateException("본인이 작성한 리뷰만 수정할 수 있습니다.");
        }

        review.update(rating, content);
        Review updated = saveReviewPort.save(review);
        log.info("리뷰 수정 완료: reviewId={}", reviewId);
        return updated;
    }

    /** 리뷰 삭제 */
    public void deleteReview(Long reviewId, Long userId) {
        log.info("리뷰 삭제 시작: reviewId={}, userId={}", reviewId, userId);

        Review review = loadReviewPort.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("리뷰를 찾을 수 없습니다. id=" + reviewId));

        if (!review.getUserId().equals(userId)) {
            throw new IllegalStateException("본인이 작성한 리뷰만 삭제할 수 있습니다.");
        }

        saveReviewPort.deleteById(reviewId);
        log.info("리뷰 삭제 완료: reviewId={}", reviewId);
    }

    /** 상품 리뷰 목록 (평점 최신순) */
    @Transactional(readOnly = true)
    public List<Review> getProductReviews(Long productId) {
        return loadReviewPort.findByProductId(productId);
    }

    /** 사용자 리뷰 목록 */
    @Transactional(readOnly = true)
    public List<Review> getUserReviews(Long userId) {
        return loadReviewPort.findByUserId(userId);
    }
}