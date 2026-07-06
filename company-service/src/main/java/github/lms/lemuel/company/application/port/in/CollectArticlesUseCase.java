package github.lms.lemuel.company.application.port.in;

/** 뉴스 기사 수집 트리거 — 단건 기업 또는 전체 기업. */
public interface CollectArticlesUseCase {

    CollectResult collectFor(String stockCode);

    CollectResult collectAll();
}
