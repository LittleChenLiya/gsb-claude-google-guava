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
import static org.junit.Assert.fail;

import com.google.common.cache.CacheStatsSampler.Snapshot;
import java.util.function.Supplier;
import junit.framework.TestCase;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Unit test for {@link CacheStatsSampler}. */
@NullUnmarked
public class CacheStatsSamplerTest extends TestCase {

  /** A controllable cumulative-stats source that can also simulate read failures. */
  private static final class FakeStatsSource implements Supplier<CacheStats> {
    private CacheStats current = new CacheStats(0, 0, 0, 0, 0, 0);
    private @Nullable RuntimeException failure;

    void set(CacheStats stats) {
      current = stats;
    }

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    void stopFailing() {
      failure = null;
    }

    @Override
    public CacheStats get() {
      if (failure != null) {
        throw failure;
      }
      return current;
    }
  }

  public void testSample_normalDelta() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    Snapshot first = sampler.sample();
    assertThat(first.generation()).isEqualTo(0);
    assertThat(first.stats()).isEqualTo(new CacheStats(10, 5, 4, 1, 100, 2));

    source.set(new CacheStats(15, 9, 6, 2, 180, 3));
    Snapshot second = sampler.sample();
    assertThat(second.generation()).isEqualTo(0);
    assertThat(second.stats()).isEqualTo(new CacheStats(5, 4, 2, 1, 80, 1));
  }

  public void testSample_zeroRequests() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    sampler.sample();

    // No cache activity between the two samples.
    Snapshot idle = sampler.sample();
    CacheStats stats = idle.stats();
    assertThat(stats).isEqualTo(new CacheStats(0, 0, 0, 0, 0, 0));
    assertThat(stats.requestCount()).isEqualTo(0);
    assertThat(stats.hitRate()).isEqualTo(1.0);
    assertThat(stats.missRate()).isEqualTo(0.0);
    assertThat(stats.loadCount()).isEqualTo(0);
    assertThat(stats.loadExceptionRate()).isEqualTo(0.0);
    assertThat(stats.averageLoadPenalty()).isEqualTo(0.0);
  }

  public void testSample_counterRollbackClampedToZero() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    sampler.sample();

    // Counters move backwards (for example, the underlying stats were reset externally).
    source.set(new CacheStats(4, 1, 1, 0, 30, 0));
    Snapshot rolledBack = sampler.sample();
    assertThat(rolledBack.stats()).isEqualTo(new CacheStats(0, 0, 0, 0, 0, 0));

    // Sampling continues from the rolled-back point.
    source.set(new CacheStats(6, 3, 2, 1, 50, 1));
    Snapshot after = sampler.sample();
    assertThat(after.stats()).isEqualTo(new CacheStats(2, 2, 1, 1, 20, 1));
  }

  public void testReset_startsNewGeneration() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    Snapshot oldGeneration = sampler.sample();
    assertThat(sampler.generation()).isEqualTo(0);

    source.set(new CacheStats(12, 6, 5, 1, 120, 2));
    sampler.reset();
    assertThat(sampler.generation()).isEqualTo(1);

    // The new generation measures from the reset point, not from zero.
    source.set(new CacheStats(15, 9, 6, 2, 180, 3));
    Snapshot newGeneration = sampler.sample();
    assertThat(newGeneration.generation()).isEqualTo(1);
    assertThat(newGeneration.stats()).isEqualTo(new CacheStats(3, 3, 1, 1, 60, 1));

    // Snapshots from the old generation cannot be combined with the new one.
    try {
      newGeneration.minus(oldGeneration);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
    try {
      oldGeneration.minus(newGeneration);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
    try {
      oldGeneration.plus(newGeneration);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
  }

  public void testSnapshotArithmetic_sameGeneration() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    Snapshot first = sampler.sample();
    source.set(new CacheStats(15, 9, 6, 2, 180, 3));
    Snapshot second = sampler.sample();

    assertThat(second.minus(first)).isEqualTo(new CacheStats(0, 0, 0, 0, 0, 0));
    assertThat(first.plus(second)).isEqualTo(new CacheStats(15, 9, 6, 2, 180, 3));
  }

  public void testSample_failureDoesNotAdvanceSamplingPoint() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    sampler.sample();

    source.failWith(new RuntimeException("boom"));
    try {
      sampler.sample();
      fail("expected RuntimeException");
    } catch (RuntimeException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("boom");
    }
    // The failed sample did not move the sampling point or the generation.
    assertThat(sampler.generation()).isEqualTo(0);

    source.stopFailing();
    source.set(new CacheStats(15, 9, 6, 2, 180, 3));
    Snapshot after = sampler.sample();
    // The interval still measures from the last successful sample, not the failed attempt.
    assertThat(after.stats()).isEqualTo(new CacheStats(5, 4, 2, 1, 80, 1));
  }

  public void testReset_failureDoesNotStartNewGeneration() {
    FakeStatsSource source = new FakeStatsSource();
    CacheStatsSampler sampler = new CacheStatsSampler(source);

    source.set(new CacheStats(10, 5, 4, 1, 100, 2));
    Snapshot before = sampler.sample();

    source.failWith(new RuntimeException("boom"));
    try {
      sampler.reset();
      fail("expected RuntimeException");
    } catch (RuntimeException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("boom");
    }
    assertThat(sampler.generation()).isEqualTo(0);

    source.stopFailing();
    source.set(new CacheStats(15, 9, 6, 2, 180, 3));
    Snapshot after = sampler.sample();
    // Still the same generation, and the interval is unaffected by the failed reset.
    assertThat(after.generation()).isEqualTo(0);
    assertThat(after.stats()).isEqualTo(new CacheStats(5, 4, 2, 1, 80, 1));
    assertThat(after.minus(before)).isEqualTo(new CacheStats(0, 0, 0, 0, 0, 0));
  }

  /**
   * Short regression example against the original {@link Cache} / {@link CacheStats} interface:
   * sampling never invokes the loader, the cache's cumulative stats are left untouched, and the
   * sampled intervals reconcile exactly with the cumulative stats.
   */
  public void testLoadingCacheRegression() {
    int[] loadCalls = {0};
    LoadingCache<String, String> cache =
        CacheBuilder.newBuilder()
            .recordStats()
            .build(
                new CacheLoader<String, String>() {
                  @Override
                  public String load(String key) {
                    loadCalls[0]++;
                    return "value-" + key;
                  }
                });
    CacheStatsSampler sampler = CacheStatsSampler.sampling(cache);

    // Generation 0: one miss (with a load) and one hit.
    assertThat(cache.getUnchecked("a")).isEqualTo("value-a");
    assertThat(cache.getUnchecked("a")).isEqualTo("value-a");
    Snapshot first = sampler.sample();
    assertThat(first.stats().hitCount()).isEqualTo(1);
    assertThat(first.stats().missCount()).isEqualTo(1);
    assertThat(first.stats().loadSuccessCount()).isEqualTo(1);
    assertThat(first.stats().loadExceptionCount()).isEqualTo(0);
    assertThat(first.stats().evictionCount()).isEqualTo(0);
    assertThat(loadCalls[0]).isEqualTo(1);

    // The original cumulative stats are unchanged by sampling.
    CacheStats cumulative = cache.stats();
    assertThat(cumulative.hitCount()).isEqualTo(1);
    assertThat(cumulative.missCount()).isEqualTo(1);
    assertThat(cumulative.loadSuccessCount()).isEqualTo(1);

    // Explicitly start a new generation; sampling and resetting perform no loads.
    sampler.reset();
    Snapshot idle = sampler.sample();
    assertThat(idle.stats().requestCount()).isEqualTo(0);
    assertThat(idle.stats().hitRate()).isEqualTo(1.0);
    assertThat(loadCalls[0]).isEqualTo(1);
    assertThat(cache.stats()).isEqualTo(cumulative);

    // Generation 1: one more miss (with a load).
    assertThat(cache.getUnchecked("b")).isEqualTo("value-b");
    Snapshot second = sampler.sample();
    assertThat(second.generation()).isEqualTo(1);
    assertThat(second.stats().hitCount()).isEqualTo(0);
    assertThat(second.stats().missCount()).isEqualTo(1);
    assertThat(second.stats().loadSuccessCount()).isEqualTo(1);
    assertThat(loadCalls[0]).isEqualTo(2);

    // Old-generation snapshots cannot be subtracted from new-generation snapshots.
    try {
      second.minus(first);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }

    // The intervals reconcile with the cumulative stats from the original interface.
    assertThat(first.stats().plus(idle.stats())).isEqualTo(cumulative);
    assertThat(cache.stats().minus(cumulative)).isEqualTo(second.stats());
  }
}
