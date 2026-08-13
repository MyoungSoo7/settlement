package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.out.DisplaySectionPort;
import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionItem;
import github.lms.lemuel.category.domain.DisplaySectionKind;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import github.lms.lemuel.category.domain.exception.CategoryNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 진열 편성 유스케이스.
 *
 * <p>노출 판정은 저장된 플래그가 아니라 도메인의 {@link DisplaySection#isVisibleAt(LocalDateTime)} 이
 * 시각을 받아 계산한다 — "기간이 끝났는데 플래그가 남아 계속 노출" 되는 사고를 구조적으로 막는다.
 * 시각은 {@link Clock} 으로 주입해 테스트가 시간에 매달리지 않게 한다.
 */
@Service
@Transactional(readOnly = true)
public class DisplaySectionService {

    private final DisplaySectionPort displaySectionPort;
    private final Clock clock;

    public DisplaySectionService(DisplaySectionPort displaySectionPort, Clock clock) {
        this.displaySectionPort = displaySectionPort;
        this.clock = clock;
    }

    /** 지금 노출 중인 편성만 (정렬 순서 순). */
    public List<DisplaySection> getVisibleSections() {
        LocalDateTime now = LocalDateTime.now(clock);
        return displaySectionPort.loadAll().stream()
                .filter(section -> section.isVisibleAt(now))
                .toList();
    }

    /** 전체 편성 (비노출 포함) — 운영 콘솔용. */
    public List<DisplaySection> getAllSections() {
        return displaySectionPort.loadAll();
    }

    public List<DisplaySectionItem> getItems(String code) {
        return displaySectionPort.loadItems(requireSection(code).getId());
    }

    @Transactional
    public DisplaySection createSection(String code, String name, DisplaySectionKind kind,
                                        Long categoryId, LocalDateTime startsAt, LocalDateTime endsAt,
                                        Integer sortOrder) {
        displaySectionPort.findByCode(code).ifPresent(existing -> {
            throw new CategoryInvariantViolationException("이미 존재하는 편성 코드입니다: " + code);
        });
        return displaySectionPort.save(DisplaySection.create(code, name, kind, categoryId,
                startsAt, endsAt, sortOrder == null ? 0 : sortOrder));
    }

    @Transactional
    public DisplaySectionItem addProduct(String code, Long productId, Integer sortOrder, boolean pinned) {
        DisplaySection section = requireSection(code);
        return displaySectionPort.saveItem(DisplaySectionItem.of(
                section.getId(), productId, sortOrder == null ? 0 : sortOrder, pinned));
    }

    @Transactional
    public void removeProduct(String code, Long productId) {
        displaySectionPort.removeItem(requireSection(code).getId(), productId);
    }

    @Transactional
    public DisplaySection reschedule(String code, LocalDateTime startsAt, LocalDateTime endsAt) {
        DisplaySection section = requireSection(code);
        section.reschedule(startsAt, endsAt);
        return displaySectionPort.save(section);
    }

    @Transactional
    public DisplaySection setActive(String code, boolean active) {
        DisplaySection section = requireSection(code);
        if (active) {
            section.activate();
        } else {
            section.deactivate();
        }
        return displaySectionPort.save(section);
    }

    private DisplaySection requireSection(String code) {
        return displaySectionPort.findByCode(code)
                .orElseThrow(() -> new CategoryNotFoundException(code));
    }
}
