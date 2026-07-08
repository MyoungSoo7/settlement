package github.lms.lemuel.market.domain;

/** 상장 시장 구분 — 금융위 주식시세정보 {@code mrktCtg} 와 1:1 (KOSPI/KOSDAQ/KONEX). */
public enum Market {
    KOSPI, KOSDAQ, KONEX;

    /** {@code mrktCtg} 문자열 → enum. 알 수 없는 값/공백은 null (해당 row skip). */
    public static Market fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toUpperCase()) {
            case "KOSPI" -> KOSPI;
            case "KOSDAQ" -> KOSDAQ;
            case "KONEX" -> KONEX;
            default -> null;
        };
    }
}
