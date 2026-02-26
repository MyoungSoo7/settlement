package github.lms.lemuel.review.adapter.out.persistence;

import github.lms.lemuel.review.application.port.out.LoadReviewPort;
import github.lms.lemuel.review.application.port.out.SaveReviewPort;
import github.lms.lemuel.review.domain.Review;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ReviewPersistenceAdapter implements SaveReviewPort, LoadReviewPort {

    private final SpringDataReviewJpaRepository repository;

    // ── SaveReviewPort ─────────────────────────────────────────────────

    @Override
    public Review save(Review review) {
        ReviewJpaEntity entity = toEntity(review);
        ReviewJpaEntity saved  = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void deleteById(Long reviewId) {
        repository.deleteById(reviewId);
    }

    // ── LoadReviewPort ─────────────────────────────────────────────────

    @Override
    public Optional<Review> findById(Long reviewId) {
        return repository.findById(reviewId).map(this::toDomain);
    }

    @Override
    public List<Review> findByProductId(Long productId) {
        return repository.findByProductIdOrderByCreatedAtDesc(productId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Review> findByUserId(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<Review> findByUserIdAndProductId(Long userId, Long productId) {
        return repository.findByUserIdAndProductId(userId, productId).map(this::toDomain);
    }

    @Override
    public boolean existsByUserIdAndProductId(Long userId, Long productId) {
        return repository.existsByUserIdAndProductId(userId, productId);
    }

    // ── Mapper ─────────────────────────────────────────────────────────

    private ReviewJpaEntity toEntity(Review domain) {
        ReviewJpaEntity entity = new ReviewJpaEntity();
        entity.setId(domain.getId());
        entity.setProductId(domain.getProductId());
        entity.setUserId(domain.getUserId());
        entity.setRating((short) domain.getRating());
        entity.setContent(domain.getContent());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private Review toDomain(ReviewJpaEntity entity) {
        Review review = new Review();
        review.setId(entity.getId());
        review.setProductId(entity.getProductId());
        review.setUserId(entity.getUserId());
        review.update(entity.getRating(), entity.getContent()); // validates rating
        review.setCreatedAt(entity.getCreatedAt()); // restore actual DB timestamps
        review.setUpdatedAt(entity.getUpdatedAt());
        return review;
    }
}
