package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.Article;

import java.util.List;

public interface SaveArticlePort {

    /**
     * 신규 기사만 저장하고 저장 건수를 돌려준다 — url_hash 중복(이미 수집된 기사)은 건너뛴다.
     * 멱등: 같은 입력을 다시 넣으면 0 을 돌려준다.
     */
    int saveNew(List<Article> articles);
}
