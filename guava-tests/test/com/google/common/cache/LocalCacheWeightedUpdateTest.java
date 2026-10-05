/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.cache;

import static com.google.common.cache.TestingWeighers.intValueWeigher;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.cache.LocalCache.LocalManualCache;
import com.google.common.cache.LocalCache.Segment;
import com.google.common.cache.LocalCache.WeightedUpdateResult;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.FakeTicker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Test;

/**
 * Acceptance tests for the explicit weight-aware update entry point {@link
 * LocalCache#replaceWeighted}.
 *
 * <p>Verifies that:
 *
 * <ol>
 *   <li>updating a small value to a large value triggers a real capacity change (weight is
 *       recomputed, not reused);
 *   <li>a negative weight (or a throwing weigher) rejects the update and keeps the old value and
 *       weight;
 *   <li>a weigher that reentrantly queries the cache never deadlocks (a JUnit barrier coordinates
 *       the contending threads);
 *   <li>removal causes are accurate (REPLACED for a committed supersession, SIZE for an oversized
 *       entry, and the two are kept separate);
 *   <li>an update that races against expiration cannot overwrite the newer (expired) generation;
 *   <li>ordinary {@code put} remains compatible; and
 *   <li>a three-value (small/medium/large) example drives real capacity and eviction behavior.
 * </ol>
 */
public class LocalCacheWeightedUpdateTest {

  private ExecutorService executor;

  @After
  public void tearDown() throws Exception {
    if (executor != null) {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, SECONDS));
    }
  }

  @SuppressWarnings("unchecked")
  private static LocalCache<Object, Integer> singleSegmentCache(CacheBuilder<?, ?> b) {
    // Force exactly one segment so totalWeight accounting is deterministic.
    return new LocalCache<>((CacheBuilder<Object, Object>) b.concurrencyLevel(1), null);
  }

  @SuppressWarnings("unchecked")
  private static Segment<Object, Integer> onlySegment(LocalCache<?, ?> cache) {
    return (Segment<Object, Integer>) cache.segments[0];
  }

  private static class RecordingListener implements RemovalListener<Object, Object> {
    final List<RemovalNotification<Object, Object>> notifications = new ArrayList<>();

    @Override
    public synchronized void onRemoval(RemovalNotification<Object, Object> notification) {
      notifications.add(notification);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CacheBuilder<Object, Object> weightedBuilderWith(
      long maximumWeight, Weigher<?, ?> weigher, RemovalListener<?, ?> listener) {
    return (CacheBuilder)
        CacheBuilder.newBuilder()
            .maximumWeight(maximumWeight)
            .weigher((Weigher) weigher)
            .removalListener((RemovalListener) listener);
  }

  /** The new weight is recomputed: small -> large triggers a real capacity change. */
  @Test
  public void testSmallToLargeTriggersRealCapacityChange() {
    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(10).weigher(intValueWeigher()));
    Segment<Object, Integer> segment = onlySegment(cache);

    cache.put("k", 2);
    assertEquals(2L, segment.totalWeight);

    WeightedUpdateResult result = cache.replaceWeighted("k", 7);
    assertEquals(WeightedUpdateResult.COMMITTED, result);
    assertEquals(Integer.valueOf(7), cache.getIfPresent("k"));
    // The weight was recomputed (7), not carried over from the old value (2): totalWeight tracks
    // the real new capacity even though the entry count is still one.
    assertEquals(7L, segment.totalWeight);
  }

  /** A negative weight rejects the update and preserves the old value and weight. */
  @Test
  public void testNegativeWeightRejectedAndOldValueRetained() {
    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(100).weigher(intValueWeigher()));
    Segment<Object, Integer> segment = onlySegment(cache);
    cache.put("k", 5);

    try {
      cache.replaceWeighted("k", -1);
      fail("Expected IllegalStateException for a negative weight");
    } catch (IllegalStateException expected) {
      // required
    }

    // Old value and old weight are fully retained.
    assertEquals(Integer.valueOf(5), cache.getIfPresent("k"));
    assertEquals(5L, segment.totalWeight);
  }

  /** A weigher that throws rejects the update and preserves the old value and weight. */
  @Test
  public void testThrowingWeigherRejectedAndOldValueRetained() {
    Weigher<Object, Integer> throwing =
        (key, value) -> {
          if (value == 9) {
            throw new IllegalStateException("weigher boom");
          }
          return value;
        };
    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(100).weigher(throwing));
    Segment<Object, Integer> segment = onlySegment(cache);
    cache.put("k", 5);

    try {
      cache.replaceWeighted("k", 9);
      fail("Expected the weigher exception to propagate");
    } catch (IllegalStateException expected) {
      assertEquals("weigher boom", expected.getMessage());
    }

    assertEquals(Integer.valueOf(5), cache.getIfPresent("k"));
    assertEquals(5L, segment.totalWeight);
  }

  /**
   * The user weigher must never run under the segment lock: when it reentrantly reads the same key
   * (same segment) from two threads at a {@link CyclicBarrier}, both updates must complete instead
   * of deadlocking. This is the required JUnit barrier command.
   */
  @Test
  public void testWeigherReentrantQueryDoesNotDeadlock() throws Exception {
    executor = Executors.newFixedThreadPool(2);
    final CyclicBarrier insideWeigher = new CyclicBarrier(2);
    final AtomicInteger weighCalls = new AtomicInteger();

    Weigher<Object, Integer> reentrantWeigher =
        new Weigher<Object, Integer>() {
          LocalCache<Object, Integer> cache;

          @Override
          public int weigh(Object key, Integer value) {
            if (value < 2) {
              // Plain weighing for the initial put; it must not touch the barrier.
              return value;
            }
            // Park both update threads *inside* the weigher. If the segment lock were held here,
            // thread B could never enter (thread A holds the only segment's lock), so the barrier
            // could never trip and this test would time out.
            try {
              insideWeigher.await(5, SECONDS);
            } catch (Exception e) {
              throw new AssertionError("barrier failed (possible deadlock)", e);
            }
            // A reentrant read of the very key being updated, on the contended segment.
            Integer peeked = cache.getIfPresent(key);
            assertNotNull(peeked);
            weighCalls.incrementAndGet();
            return value;
          }
        };

    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(1000).weigher(reentrantWeigher));
    // Wire the cache into the weigher after construction (avoids capturing it before it exists).
    setCacheField(reentrantWeigher, cache);

    cache.put("k", 1);

    Future<WeightedUpdateResult> f1 = executor.submit(() -> cache.replaceWeighted("k", 2));
    Future<WeightedUpdateResult> f2 = executor.submit(() -> cache.replaceWeighted("k", 3));

    // Both threads rendezvoused inside the weigher (proving it ran unlocked), then serialize on
    // the segment lock. Exactly one update commits; the other is rejected by the generation
    // check because it weighed the same superseded generation. Crucially neither one blocks.
    WeightedUpdateResult r1 = f1.get(10, SECONDS);
    WeightedUpdateResult r2 = f2.get(10, SECONDS);
    List<WeightedUpdateResult> results =
        com.google.common.collect.Lists.newArrayList(r1, r2);
    assertTrue(
        "expected exactly one COMMITTED, got " + results,
        results.stream().filter(r -> r == WeightedUpdateResult.COMMITTED).count() == 1L);
    assertTrue(
        "expected exactly one REJECTED, got " + results,
        results.stream().filter(r -> r == WeightedUpdateResult.REJECTED).count() == 1L);
    assertEquals(2, weighCalls.get());

    Integer finalValue = cache.getIfPresent("k");
    assertTrue(finalValue.equals(2) || finalValue.equals(3));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void setCacheField(Weigher weigher, LocalCache cache) throws Exception {
    java.lang.reflect.Field f = weigher.getClass().getDeclaredField("cache");
    f.setAccessible(true);
    f.set(weigher, cache);
  }

  /**
   * Removal causes are accurate and separated: a committed supersession reports REPLACED (only
   * after the real commit), while an entry whose new weight alone exceeds capacity reports SIZE.
   */
  @Test
  public void testRemovalCausesAccurateAndSeparated() {
    RecordingListener listener = new RecordingListener();
    LocalCache<Object, Integer> cache =
        singleSegmentCache(
            weightedBuilderWith(
                10, TestingWeighers.intValueWeigher(), listener));

    cache.put("k", 4);
    listener.notifications.clear();

    // 4 -> 3: committed replacement only; nothing oversized.
    assertEquals(WeightedUpdateResult.COMMITTED, cache.replaceWeighted("k", 3));
    assertEquals(1, listener.notifications.size());
    RemovalNotification<Object, Object> replaced = listener.notifications.get(0);
    assertEquals("k", replaced.getKey());
    assertEquals(4, replaced.getValue());
    assertEquals(RemovalCause.REPLACED, replaced.getCause());

    listener.notifications.clear();

    // 3 -> 20: commits (REPLACED for the 3), then the oversized entry is evicted (SIZE). The two
    // causes must be distinct and ordered.
    assertEquals(WeightedUpdateResult.COMMITTED, cache.replaceWeighted("k", 20));
    assertEquals(2, listener.notifications.size());
    assertEquals(RemovalCause.REPLACED, listener.notifications.get(0).getCause());
    assertEquals(20, listener.notifications.get(1).getValue());
    assertEquals(RemovalCause.SIZE, listener.notifications.get(1).getCause());
    assertNull(cache.getIfPresent("k"));
    assertEquals(0L, onlySegment(cache).totalWeight);
  }

  /** A failed weighing enqueues no replacement notification (callbacks only after real commit). */
  @Test
  public void testNoCallbackWhenWeighingFails() {
    RecordingListener listener = new RecordingListener();
    LocalCache<Object, Integer> cache =
        singleSegmentCache(
            weightedBuilderWith(100, TestingWeighers.intValueWeigher(), listener));
    cache.put("k", 5);
    listener.notifications.clear();

    try {
      cache.replaceWeighted("k", -1);
      fail();
    } catch (IllegalStateException expected) {
      // required
    }
    assertTrue(listener.notifications.toString(), listener.notifications.isEmpty());
    assertEquals(Integer.valueOf(5), cache.getIfPresent("k"));
  }

  /**
   * Expiration generation cannot be overwritten by an old update: if the entry expires between the
   * out-of-lock weighing and the locked commit, the stale update is rejected and the expired entry
   * is retired with EXPIRED rather than resurrected.
   */
  @Test
  public void testExpiredGenerationNotOverwrittenByStaleUpdate() {
    FakeTicker ticker = new FakeTicker();
    RecordingListener listener = new RecordingListener();

    // A weigher that expires the entry while the update is between the (unlocked) weighing and the
    // locked generation re-validation.
    Weigher<Object, Integer> expiringWeigher =
        (key, value) -> {
          if (value == 6) {
            // Age past expiration only while the update is between weighing and commit.
            ticker.advance(20, MILLISECONDS);
          }
          return value;
        };
    @SuppressWarnings({"unchecked", "rawtypes"})
    CacheBuilder<Object, Object> builder =
        (CacheBuilder)
            CacheBuilder.newBuilder()
                .maximumWeight(100)
                .expireAfterWrite(10, MILLISECONDS)
                .weigher((Weigher) expiringWeigher)
                .ticker(ticker)
                .removalListener((RemovalListener) listener);
    LocalCache<Object, Integer> cache = singleSegmentCache(builder);

    cache.put("k", 5);
    listener.notifications.clear();

    WeightedUpdateResult result = cache.replaceWeighted("k", 6);
    // The weighed generation is no longer live under the lock: the update must not overwrite it.
    assertEquals(WeightedUpdateResult.REJECTED, result);
    cache.cleanUp();
    assertNull(cache.getIfPresent("k"));

    boolean sawExpired = false;
    for (RemovalNotification<Object, Object> n : listener.notifications) {
      if (n.getCause() == RemovalCause.EXPIRED) {
        sawExpired = true;
        assertEquals(5, n.getValue());
      }
    }
    assertTrue("expected an EXPIRED notification, got " + listener.notifications, sawExpired);
  }

  /** Ordinary put stays compatible and re-weighs values for the standard path. */
  @Test
  public void testOrdinaryPutRemainsCompatible() {
    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(100).weigher(intValueWeigher()));
    Segment<Object, Integer> segment = onlySegment(cache);

    assertNull(cache.put("k", 10));
    assertEquals(Integer.valueOf(10), cache.put("k", 20));
    assertEquals(Integer.valueOf(20), cache.getIfPresent("k"));
    assertEquals(20L, segment.totalWeight);

    // putIfAbsent still returns the existing value without replacing it.
    assertEquals(Integer.valueOf(20), cache.putIfAbsent("k", 30));
    assertEquals(Integer.valueOf(20), cache.getIfPresent("k"));
  }

  /** Absent key: explicit update does nothing and reports ABSENT; put still fills it. */
  @Test
  public void testAbsentKeyIsRejectedAndPutStillWorks() {
    LocalCache<Object, Integer> cache =
        singleSegmentCache(CacheBuilder.newBuilder().maximumWeight(100).weigher(intValueWeigher()));
    assertEquals(WeightedUpdateResult.ABSENT, cache.replaceWeighted("missing", 1));
    assertNull(cache.getIfPresent("missing"));

    cache.put("missing", 1);
    assertEquals(WeightedUpdateResult.COMMITTED, cache.replaceWeighted("missing", 2));
    assertEquals(Integer.valueOf(2), cache.getIfPresent("missing"));
  }

  /**
   * Three-value example: small (2) -> medium (6) -> large (12) against maximumWeight(10). Small and
   * medium fit with real weight changes; the large value commits but is immediately size-evicted.
   */
  @Test
  public void testThreeValueExampleSmallMediumLarge() {
    RecordingListener listener = new RecordingListener();
    LocalCache<Object, Integer> cache =
        singleSegmentCache(
            weightedBuilderWith(10, TestingWeighers.intValueWeigher(), listener));
    Segment<Object, Integer> segment = onlySegment(cache);

    // small
    cache.put("k", 2);
    assertEquals(2L, segment.totalWeight);

    // medium: real capacity change from 2 to 6
    assertEquals(WeightedUpdateResult.COMMITTED, cache.replaceWeighted("k", 6));
    assertEquals(Integer.valueOf(6), cache.getIfPresent("k"));
    assertEquals(6L, segment.totalWeight);

    // large: weight 12 > segment max 10; it commits then is evicted for SIZE on its own.
    assertEquals(WeightedUpdateResult.COMMITTED, cache.replaceWeighted("k", 12));
    assertNull(cache.getIfPresent("k"));
    assertEquals(0L, segment.totalWeight);

    // Notification history for the three values: 2 REPLACED by 6, 6 REPLACED by 12, then 12 SIZE.
    ImmutableList<RemovalCause> causes =
        ImmutableList.copyOf(
            listener.notifications.stream()
                .map(RemovalNotification::getCause)
                .collect(Collectors.toList()));
    assertEquals(
        ImmutableList.of(RemovalCause.REPLACED, RemovalCause.REPLACED, RemovalCause.SIZE), causes);
    assertEquals(2, listener.notifications.get(0).getValue());
    assertEquals(6, listener.notifications.get(1).getValue());
    assertEquals(12, listener.notifications.get(2).getValue());
  }

  /** Sanity check through the {@link LocalManualCache} wrapper entry point as well. */
  @Test
  public void testEntryPointExposedOnLocalManualCache() {
    LocalManualCache<Object, Integer> manual =
        new LocalManualCache<>(
            CacheBuilder.newBuilder().maximumWeight(100).weigher(intValueWeigher()));
    manual.put("k", 3);
    WeightedUpdateResult result = manual.replaceWeighted("k", 8);
    assertEquals(WeightedUpdateResult.COMMITTED, result);
    assertSame(Integer.valueOf(8), manual.getIfPresent("k"));
  }
}
