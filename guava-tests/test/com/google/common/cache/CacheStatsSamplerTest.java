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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;
import org.jspecify.annotations.NullUnmarked;

/** Unit test for {@link CacheStatsSampler}. */
@NullUnmarked
public class CacheStatsSamplerTest extends TestCase {

  /** A small, directly constructible stats source: no real cache needed. */
  private static final class FakeStatsSource implements Supplier<CacheStats> {
    CacheStats current = new CacheStats(0, 0, 0, 0, 0, 0);
    RuntimeException failure = null;
    int calls = 0;

    @Override
    public CacheStats get() {
      calls++;
      if (failure != null) {
        throw failure;
      }
      return current;
    }
  }

  private static final CacheStats ONE = new CacheStats(11, 13, 17, 19, 23, 27);
  private static final CacheStats TWO = new CacheStats(53, 47, 43, 41, 37, 31);

  public void testIntervalDelta_normalDifference() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    source.current = ONE;
    CacheStatsSampler.Snapshot first = sampler.sample();
    // First sample of a generation measures from zero.
    assertThat(first.delta()).isEqualTo(ONE);
    assertThat(first.cumulative()).isEqualTo(ONE);

    source.current = TWO;
    CacheStatsSampler.Snapshot second = sampler.sample();
    assertThat(second.delta().requestCount()).isEqualTo(76);
    assertThat(second.delta().hitCount()).isEqualTo(42);
    assertThat(second.delta().missCount()).isEqualTo(34);
    assertThat(second.delta().loadSuccessCount()).isEqualTo(26);
    assertThat(second.delta().loadExceptionCount()).isEqualTo(22);
    assertThat(second.delta().totalLoadTime()).isEqualTo(14);
    assertThat(second.delta().evictionCount()).isEqualTo(4);

    // Short regression check against the original interface: the sampler interval must
    // reconcile exactly with CacheStats.minus on the raw cumulative snapshots.
    assertThat(second.delta()).isEqualTo(TWO.minus(ONE));
    assertThat(second.deltaSince(first)).isEqualTo(TWO.minus(ONE));
  }

  public void testIntervalDelta_zeroRequests() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    CacheStatsSampler.Snapshot snapshot = sampler.sample();
    assertThat(snapshot.delta().requestCount()).isEqualTo(0);
    assertThat(snapshot.delta().hitRate()).isEqualTo(1.0);
    assertThat(snapshot.delta().missRate()).isEqualTo(0.0);
    assertThat(snapshot.delta().loadExceptionRate()).isEqualTo(0.0);
    assertThat(snapshot.delta().averageLoadPenalty()).isEqualTo(0.0);

    source.current = new CacheStats(0, 0, 0, 0, 0, 0);
    snapshot = sampler.sample();
    assertThat(snapshot.delta().requestCount()).isEqualTo(0);
    assertThat(snapshot.delta().hitRate()).isEqualTo(1.0);
  }

  public void testIntervalDelta_counterRollbackClampedToZero() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    source.current = TWO;
    sampler.sample();

    // Counters move backwards (e.g. the underlying stats were reset): clamp to zero.
    source.current = ONE;
    CacheStatsSampler.Snapshot snapshot = sampler.sample();
    assertThat(snapshot.delta()).isEqualTo(new CacheStats(0, 0, 0, 0, 0, 0));
    // The cumulative view still reports the raw source value.
    assertThat(snapshot.cumulative()).isEqualTo(ONE);
  }

  public void testResetStartsNewGeneration_oldSnapshotsRejected() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    source.current = ONE;
    CacheStatsSampler.Snapshot oldGeneration = sampler.sample();
    assertThat(oldGeneration.generation()).isEqualTo(0);

    source.current = TWO;
    sampler.reset();
    assertThat(sampler.generation()).isEqualTo(1);

    source.current = TWO.plus(ONE);
    CacheStatsSampler.Snapshot newGeneration = sampler.sample();
    assertThat(newGeneration.generation()).isEqualTo(1);
    // First interval of the new generation measures from the reset point, not from zero.
    assertThat(newGeneration.delta()).isEqualTo(ONE);

    // Snapshots from the old generation cannot be subtracted from the new one, in either
    // direction.
    try {
      newGeneration.deltaSince(oldGeneration);
      fail("expected IllegalArgumentException for cross-generation subtraction");
    } catch (IllegalArgumentException expected) {
    }
    try {
      oldGeneration.deltaSince(newGeneration);
      fail("expected IllegalArgumentException for cross-generation subtraction");
    } catch (IllegalArgumentException expected) {
    }
  }

  public void testDeltaSince_rejectsLaterSnapshot() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    source.current = ONE;
    CacheStatsSampler.Snapshot first = sampler.sample();
    source.current = TWO;
    CacheStatsSampler.Snapshot second = sampler.sample();

    assertThat(second.deltaSince(first)).isEqualTo(TWO.minus(ONE));
    try {
      first.deltaSince(second);
      fail("expected IllegalArgumentException for reversed subtraction");
    } catch (IllegalArgumentException expected) {
    }
  }

  public void testFailureDoesNotAdvanceSamplingPoint() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = CacheStatsSampler.of(source);

    source.current = ONE;
    sampler.sample();

    source.failure = new RuntimeException("boom");
    try {
      sampler.sample();
      fail("expected the source failure to propagate");
    } catch (RuntimeException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("boom");
    }

    // The failed sample did not move the sampling point: the next delta still measures from ONE.
    source.failure = null;
    source.current = TWO;
    CacheStatsSampler.Snapshot afterFailure = sampler.sample();
    assertThat(afterFailure.delta()).isEqualTo(TWO.minus(ONE));

    // Same for reset(): a failing source leaves the generation untouched.
    source.failure = new RuntimeException("boom");
    try {
      sampler.reset();
      fail("expected the source failure to propagate");
    } catch (RuntimeException expected) {
    }
    assertThat(sampler.generation()).isEqualTo(0);
  }

  public void testRealCache_samplingDoesNotCallLoader_cumulativeStatsUnchanged() {
    AtomicInteger loaderCalls = new AtomicInteger();
    CacheLoader<String, String> loader =
        new CacheLoader<String, String>() {
          @Override
          public String load(String key) {
            loaderCalls.incrementAndGet();
            return "value-" + key;
          }
        };
    LoadingCache<String, String> cache = CacheBuilder.newBuilder().recordStats().build(loader);

    // One miss (load) + one hit.
    assertThat(cache.getUnchecked("a")).isEqualTo("value-a");
    assertThat(cache.getUnchecked("a")).isEqualTo("value-a");
    assertThat(loaderCalls.get()).isEqualTo(1);

    CacheStatsSampler sampler = CacheStatsSampler.of(cache);
    CacheStatsSampler.Snapshot first = sampler.sample();
    assertThat(first.delta().hitCount()).isEqualTo(1);
    assertThat(first.delta().missCount()).isEqualTo(1);
    assertThat(first.delta().loadSuccessCount()).isEqualTo(1);

    // Sampling itself performs no lookups and hence never calls the loader.
    sampler.sample();
    sampler.reset();
    sampler.sample();
    assertThat(loaderCalls.get()).isEqualTo(1);

    // More traffic in the new generation: one more miss (load) + one hit.
    assertThat(cache.getUnchecked("b")).isEqualTo("value-b");
    assertThat(cache.getUnchecked("b")).isEqualTo("value-b");
    assertThat(loaderCalls.get()).isEqualTo(2);

    CacheStatsSampler.Snapshot second = sampler.sample();
    assertThat(second.delta().hitCount()).isEqualTo(1);
    assertThat(second.delta().missCount()).isEqualTo(1);
    assertThat(second.delta().loadSuccessCount()).isEqualTo(1);

    // The cache cumulative stats are unchanged by sampling and still reconcile with the
    // per-generation intervals via the original CacheStats interface.
    CacheStats cumulative = cache.stats();
    assertThat(cumulative.hitCount()).isEqualTo(2);
    assertThat(cumulative.missCount()).isEqualTo(2);
    assertThat(cumulative.loadSuccessCount()).isEqualTo(2);
    assertThat(second.delta()).isEqualTo(cumulative.minus(first.cumulative()));
    assertThat(first.delta().plus(second.delta())).isEqualTo(cumulative);
  }
}
