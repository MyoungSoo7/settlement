package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardCapturePort;
import github.lms.lemuel.card.application.port.out.SaveCardCapturePort;
import github.lms.lemuel.card.domain.CardCapture;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CardCapturePersistenceAdapter
        implements LoadCardCapturePort, SaveCardCapturePort {

    private final SpringDataCardCaptureRepository repository;

    public CardCapturePersistenceAdapter(SpringDataCardCaptureRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CardCapture> findByCaptureId(String captureId) {
        return repository.findByCaptureId(captureId)
                .map(CardCaptureJpaEntity::toDomain);
    }

    @Override
    public CardCapture save(CardCapture capture) {
        return repository.saveAndFlush(CardCaptureJpaEntity.fromDomain(capture)).toDomain();
    }
}
