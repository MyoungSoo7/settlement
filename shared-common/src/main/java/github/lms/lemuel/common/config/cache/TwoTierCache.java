package github.lms.lemuel.common.config.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 2-tier 캐시: L1(Caffeine, 인스턴스 로컬) + L2(Redis, 공유).
 *
 * <p>조회 경로: L1 hit → 반환 / L1 miss → L2 조회 후 적중 시 L1 채우고 반환 / 둘 다 miss → null(로더 호출).
 * 쓰기 경로: L1·L2 동시 기록 + 다른 인스턴스 L1 무효화 Pub/Sub.
 *
 * <p><b>graceful degrade</b>: L2(Redis) 호출은 모두 try/catch 로 감싸 Redis 장애 시 L1/DB 로 폴백한다.
 * Redis 가 죽어도 애플리케이션은 (로컬 캐시/DB 로) 계속 동작한다.
 *
 * <p><b>null 값</b>: NullValue 는 L1 에만 두고 L2(Redis)에는 쓰지 않는다(직렬화 회피). 실제 캐시 대상
 * 메서드는 non-null 을 반환하므로 일반 경로엔 영향 없다.
 */
public class TwoTierCache extends AbstractValueAdaptingCache {

    private static final Logger log = LoggerFactory.getLogger(TwoTierCache.class);

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    private final RedisTemplate<String, Object> l2;
    private final Duration l2Ttl;
    private final CacheInvalidationPublisher publisher;

    public TwoTierCache(String name,
                        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                        RedisTemplate<String, Object> l2,
                        Duration l2Ttl,
                        CacheInvalidationPublisher publisher,
                        boolean allowNullValues) {
        super(allowNullValues);
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
        this.l2Ttl = l2Ttl;
        this.publisher = publisher;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    @Override
    @Nullable
    protected Object lookup(Object key) {
        String k = str(key);
        Object v = l1.getIfPresent(k);
        if (v != null) {
            return v;
        }
        Object fromL2 = l2Get(k);
        if (fromL2 != null) {
            l1.put(k, fromL2);   // L2 적중분을 L1 으로 승격(near-cache)
        }
        return fromL2;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object existing = lookup(key);
        if (existing != null) {
            return (T) fromStoreValue(existing);
        }
        // 로드-스루: 캐시 미스 시 로더 실행 후 양 계층에 기록.
        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        String k = str(key);
        Object storeValue = toStoreValue(value);
        l1.put(k, storeValue);
        if (!(storeValue instanceof NullValue)) {
            l2Put(k, storeValue);
        }
        publisher.publishEvict(name, k);   // 타 인스턴스의 stale L1 무효화 → 다음 조회 시 L2 에서 최신값
    }

    @Override
    public void evict(Object key) {
        String k = str(key);
        l1.invalidate(k);
        l2Delete(k);
        publisher.publishEvict(name, k);
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        l2Clear();
        publisher.publishClear(name);
    }

    // --- Pub/Sub 수신 시 로컬 L1 만 조작 (L2 미터치, 재발행 없음) ---

    void evictLocal(Object key) {
        l1.invalidate(key);
    }

    void clearLocal() {
        l1.invalidateAll();
    }

    // --- L2(Redis) 접근 — 전부 graceful degrade. key 는 이미 문자열화된 값(k). ---

    @Nullable
    private Object l2Get(String k) {
        try {
            return l2.opsForValue().get(redisKey(k));
        } catch (RuntimeException e) {
            log.warn("L2(Redis) get failed, falling back. cache={}, key={}, error={}", name, k, e.getMessage());
            return null;
        }
    }

    private void l2Put(String k, Object storeValue) {
        try {
            l2.opsForValue().set(redisKey(k), storeValue, l2Ttl);
        } catch (RuntimeException e) {
            log.warn("L2(Redis) put failed, L1 only. cache={}, key={}, error={}", name, k, e.getMessage());
        }
    }

    private void l2Delete(String k) {
        try {
            l2.delete(redisKey(k));
        } catch (RuntimeException e) {
            log.warn("L2(Redis) delete failed. cache={}, key={}, error={}", name, k, e.getMessage());
        }
    }

    private void l2Clear() {
        try {
            Set<String> keys = l2.keys(name + "::*");
            if (keys != null && !keys.isEmpty()) {
                l2.delete(keys);
            }
        } catch (RuntimeException e) {
            log.warn("L2(Redis) clear failed. cache={}, error={}", name, e.getMessage());
        }
    }

    private String redisKey(String k) {
        return name + "::" + k;
    }

    /** Spring 이 넘기는 키 객체(Long/String 등)를 분산 캐시·Pub/Sub 에서 일관되도록 문자열로 정규화. */
    private static String str(Object key) {
        return String.valueOf(key);
    }
}
