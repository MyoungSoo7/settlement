package github.lms.lemuel.ai.rag.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 임베딩 벡터 값 객체 — 순수 도메인(프레임워크·JDBC 의존 없음).
 *
 * <p><b>정규화를 도메인에 둔 이유:</b> Google 문서는 {@code gemini-embedding-001} 의 출력을
 * 기본 3072 차원이 아닌 값으로 잘라 쓸 때 <b>L2 정규화를 직접 해야 한다</b>고 명시한다
 * (MRL 로 학습돼 앞쪽 차원만 잘라도 의미가 보존되지만, 잘린 벡터는 단위벡터가 아니다).
 * 정규화를 빼먹으면 코사인 유사도가 벡터 길이에 오염돼 <i>에러 없이 순위가 틀린다</i> —
 * 어댑터의 성실함에 맡길 성질이 아니라 값 객체의 불변식으로 못박는다.
 *
 * <p>정규화 정의:
 * <pre>
 *   norm(v) = sqrt(sum(v_i^2)),   v_hat = v / norm(v)
 * </pre>
 * 정규화된 벡터끼리는 코사인 거리와 내적의 순위가 일치하므로, 인덱스 연산자
 * ({@code vector_cosine_ops}) 선택과 무관하게 결과가 안정적이다.
 */
public final class Embedding {

    /** pgvector 의 vector 타입은 2000 차원까지만 인덱스 가능 — 그 이상은 전량 스캔이 된다(공식 README). */
    public static final int MAX_INDEXABLE_DIMENSION = 2000;

    private final float[] values;

    private Embedding(float[] values) {
        this.values = values;
    }

    /** 원시 배열로부터 생성 — 방어적 복사. */
    public static Embedding of(float... values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("임베딩 벡터는 비어 있을 수 없습니다");
        }
        return new Embedding(values.clone());
    }

    /** LLM 응답(JSON number 배열)으로부터 생성 — float 로 좁힌다(pgvector vector 는 4바이트 float). */
    public static Embedding of(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("임베딩 벡터는 비어 있을 수 없습니다");
        }
        float[] array = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null || v.isNaN() || v.isInfinite()) {
                throw new IllegalArgumentException("임베딩 벡터에 유효하지 않은 값이 있습니다: index=" + i);
            }
            array[i] = v.floatValue();
        }
        return new Embedding(array);
    }

    public int dimension() {
        return values.length;
    }

    /** 방어적 복사 — 값 객체의 불변성 유지. */
    public float[] values() {
        return values.clone();
    }

    /** L2 노름. */
    public double norm() {
        double sum = 0.0;
        for (float v : values) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * L2 정규화된 벡터를 반환한다. 이미 단위벡터면 같은 값이 나온다(멱등).
     * 영벡터는 정규화가 정의되지 않으므로 거부한다 — 조용히 통과시키면 NaN 이 DB 로 들어간다.
     */
    public Embedding l2Normalized() {
        double norm = norm();
        if (norm == 0.0) {
            throw new IllegalArgumentException("영벡터는 정규화할 수 없습니다 (임베딩 응답이 비정상입니다)");
        }
        float[] normalized = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            normalized[i] = (float) (values[i] / norm);
        }
        return new Embedding(normalized);
    }

    /**
     * pgvector 리터럴 {@code [v1,v2,...]} 로 직렬화한다.
     *
     * <p>JDBC 에 커스텀 타입을 등록하지 않고 {@code ?::vector} 캐스팅으로 넘기기 위한 표현.
     * (Hibernate 로 vector 컬럼을 매핑하지 않는 이유는 ADR 0034 §설계 참조 — ddl-auto=validate)
     */
    public String toPgVectorLiteral() {
        StringBuilder sb = new StringBuilder(values.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Embedding other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    /** 벡터 원소는 로그에 남길 가치가 없고 양이 많다 — 차원만 노출한다. */
    @Override
    public String toString() {
        return "Embedding(dimension=" + values.length + ")";
    }
}
