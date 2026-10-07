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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Samples {@link CacheStats} from a {@link Cache} (or any other cumulative-stats source) as a
 * sequence of per-interval snapshots, grouped into "generations."
 *
 * <p>The sampler reads only the cumulative stats exposed by the existing {@link Cache#stats}
 * interface; it never triggers cache loads, and it never modifies the underlying cumulative stats.
 * Each call to {@link #sample} returns the stats accumulated since the previous sample (or since
 * the last {@link #reset} or construction) and advances the sampling point. If the stats source
 * throws while sampling, neither the sampling point nor the generation is modified, so a later
 * successful sample still measures from the last successful sampling point.
 *
 * <p>Calling {@link #reset} explicitly starts a new generation: subsequent intervals are measured
 * from the cumulative stats at the time of the reset, and {@link Snapshot}s from different
 * generations cannot be subtracted from (or added to) one another.
 *
 * <p>Interval computation inherits the behavior of {@link CacheStats#minus}: an interval with zero
 * requests reports {@link CacheStats#hitRate} {@code 1.0} and {@link CacheStats#missRate} {@code
 * 0.0}, and counter rollback (a cumulative count that is smaller than at the previous sampling
 * point) is clamped up to zero rather than producing negative values.
 *
 * <p>All methods are thread-safe.
 *
 * @since NEXT
 */
@GwtCompatible
public final class CacheStatsSampler {
  private static final CacheStats ZERO = new CacheStats(0, 0, 0, 0, 0, 0);

  private final Supplier<CacheStats> statsSource;

  @GuardedBy("this")
  private long generation;

  @GuardedBy("this")
  private CacheStats samplingPoint;

  /**
   * Returns a sampler that reads cumulative stats from {@code cache} through {@link Cache#stats}.
   * Sampling never invokes the cache's loader and never modifies the cache's cumulative stats.
   */
  public static CacheStatsSampler sampling(Cache<?, ?> cache) {
    return new CacheStatsSampler(checkNotNull(cache)::stats);
  }

  /**
   * Creates a sampler that reads cumulative stats from {@code statsSource}, typically a method
   * reference such as {@code cache::stats}. The first sample measures from zero, so it reports all
   * stats accumulated by the source up to that point.
   */
  public CacheStatsSampler(Supplier<CacheStats> statsSource) {
    this.statsSource = checkNotNull(statsSource);
    this.samplingPoint = ZERO;
  }

  /** Returns the current generation, incremented by each successful {@link #reset} call. */
  public synchronized long generation() {
    return generation;
  }

  /**
   * Reads the current cumulative stats, returns a {@link Snapshot} of the interval since the
   * previous sampling point, and advances the sampling point to the stats just read.
   *
   * <p>If the stats source throws, the exception propagates and the sampler is left unchanged: the
   * sampling point is not advanced and the generation is not modified.
   */
  public synchronized Snapshot sample() {
    CacheStats current = statsSource.get();
    // CacheStats.minus clamps counter rollback up to zero.
    CacheStats interval = current.minus(samplingPoint);
    samplingPoint = current;
    return new Snapshot(generation, interval);
  }

  /**
   * Starts a new generation based at the current cumulative stats. Snapshots taken after this call
   * cannot be {@linkplain Snapshot#minus subtracted} from (or {@linkplain Snapshot#plus added} to)
   * snapshots taken before it.
   *
   * <p>If the stats source throws, the exception propagates and the sampler is left unchanged: the
   * sampling point is not advanced and the generation is not modified.
   */
  public synchronized void reset() {
    CacheStats current = statsSource.get();
    samplingPoint = current;
    generation++;
  }

  /**
   * An immutable snapshot of the stats accumulated during one interval of a generation. Instances
   * of this class are immutable.
   */
  public static final class Snapshot {
    private final long generation;
    private final CacheStats stats;

    private Snapshot(long generation, CacheStats stats) {
      this.generation = generation;
      this.stats = stats;
    }

    /** Returns the generation in which this snapshot was taken. */
    public long generation() {
      return generation;
    }

    /** Returns the stats accumulated during the interval this snapshot covers. */
    public CacheStats stats() {
      return stats;
    }

    /**
     * Returns the difference between this snapshot's stats and {@code other}'s, with negative
     * values rounded up to zero as in {@link CacheStats#minus}.
     *
     * @throws IllegalArgumentException if {@code other} was taken in a different generation
     */
    public CacheStats minus(Snapshot other) {
      checkSameGeneration(other);
      return stats.minus(other.stats);
    }

    /**
     * Returns the sum of this snapshot's stats and {@code other}'s, as in {@link CacheStats#plus}.
     *
     * @throws IllegalArgumentException if {@code other} was taken in a different generation
     */
    public CacheStats plus(Snapshot other) {
      checkSameGeneration(other);
      return stats.plus(other.stats);
    }

    private void checkSameGeneration(Snapshot other) {
      checkArgument(
          generation == other.generation,
          "Cannot combine snapshots from different generations: %s vs %s",
          generation,
          other.generation);
    }

    @Override
    public int hashCode() {
      return Objects.hash(generation, stats);
    }

    @Override
    public boolean equals(@Nullable Object object) {
      if (object instanceof Snapshot) {
        Snapshot other = (Snapshot) object;
        return generation == other.generation && stats.equals(other.stats);
      }
      return false;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("generation", generation)
          .add("stats", stats)
          .toString();
    }
  }
}
