package github.lms.lemuel.menu.application.port.out;

import github.lms.lemuel.menu.domain.Menu;

public interface SaveMenuPort {
    Menu save(Menu menu);
    void deleteById(Long id);
}
