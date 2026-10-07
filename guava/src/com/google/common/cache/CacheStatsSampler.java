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
import com.google.common.base.Supplier;

/**
 * Samples interval statistics from a cumulative {@link CacheStats} source, such as {@link
 * Cache#stats}.
 *
 * <p>A {@code CacheStatsSampler} turns the cumulative, monotonically growing counters exposed by
 * {@link Cache#stats()} into per-interval ("generation") deltas:
 *
 * <ul>
 *   <li>Each {@link #sample()} call returns a {@link Snapshot} whose {@linkplain Snapshot#delta()
 *       delta} covers the interval since the previous successful sample (or since the sampler was
 *       created / {@linkplain #reset() reset}).
 *   <li>{@link #reset()} explicitly starts a new generation. Snapshots carry their generation
 *       number, and {@link Snapshot#deltaSince} refuses to subtract snapshots from different
 *       generations, so a stale pre-reset snapshot can never be mixed with a post-reset one.
 *   <li>Interval computation reuses {@link CacheStats#minus}, so counter rollback (a cumulative
 *       counter moving backwards, for example after a stats reset in the underlying cache) is
 *       clamped to zero, and intervals with zero requests report the usual {@code 1.0} hit rate /
 *       {@code 0.0} miss and load-exception rates.
 *   <li>If the underlying stats source throws, the failure is propagated and the current sampling
 *       point is <i>not</i> advanced: the next successful sample still measures from the last
 *       successful one.
 * </ul>
 *
 * <p>Sampling only reads {@link Cache#stats}; it never performs cache lookups, so it never invokes
 * the cache's loader, and it never modifies the cache's own cumulative statistics.
 *
 * @since NEXT
 */
@GwtCompatible
public final class CacheStatsSampler {
  private static final CacheStats ZERO = new CacheStats(0, 0, 0, 0, 0, 0);

  private final Supplier<CacheStats> statsSource;

  // All mutable state below is guarded by this.
  private long generation;
  private long sequence;
  private CacheStats baseline;

  private CacheStatsSampler(Supplier<CacheStats> statsSource) {
    this.statsSource = checkNotNull(statsSource);
    this.generation = 0;
    this.sequence = 0;
    this.baseline = ZERO;
  }

  /**
   * Returns a sampler that reads cumulative statistics from {@code cache} via {@link Cache#stats}.
   */
  public static CacheStatsSampler of(Cache<?, ?> cache) {
    checkNotNull(cache);
    return new CacheStatsSampler(cache::stats);
  }

  /**
   * Returns a sampler that reads cumulative statistics from {@code statsSource}. If {@code
   * statsSource} throws, the sampling point is left untouched.
   */
  public static CacheStatsSampler of(Supplier<CacheStats> statsSource) {
    return new CacheStatsSampler(statsSource);
  }

  /** Returns the current generation number, incremented by every successful {@link #reset()}. */
  public synchronized long generation() {
    return generation;
  }

  /**
   * Starts a new generation: subsequent {@linkplain #sample() samples} measure intervals from this
   * point, and their snapshots can no longer be subtracted from pre-reset snapshots. If the stats
   * source throws, the generation and sampling point are left untouched.
   */
  public synchronized void reset() {
    CacheStats current = statsSource.get();
    generation++;
    sequence = 0;
    baseline = current;
  }

  /**
   * Takes a sample of the underlying cumulative statistics and returns a {@link Snapshot} of the
   * interval since the previous successful sample (or since creation / {@link #reset()}). If the
   * stats source throws, the sampling point is not advanced.
   */
  public synchronized Snapshot sample() {
    CacheStats current = statsSource.get();
    // CacheStats.minus clamps negative differences (counter rollback) to zero.
    CacheStats delta = current.minus(baseline);
    baseline = current;
    return new Snapshot(generation, sequence++, current, delta);
  }

  /**
   * An immutable point-in-time sample produced by {@link CacheStatsSampler#sample()}. A snapshot
   * belongs to exactly one generation of its sampler and can only be subtracted from snapshots of
   * the same generation.
   */
  @GwtCompatible
  public static final class Snapshot {
    private final long generation;
    private final long sequence;
    private final CacheStats cumulative;
    private final CacheStats delta;

    private Snapshot(long generation, long sequence, CacheStats cumulative, CacheStats delta) {
      this.generation = generation;
      this.sequence = sequence;
      this.cumulative = cumulative;
      this.delta = delta;
    }

    /** Returns the generation of the sampler that produced this snapshot. */
    public long generation() {
      return generation;
    }

    /** Returns the cumulative statistics as reported by the source at sampling time. */
    public CacheStats cumulative() {
      return cumulative;
    }

    /**
     * Returns the interval statistics since the previous successful sample of the same generation
     * (or since sampler creation / reset for the first sample of a generation).
     */
    public CacheStats delta() {
      return delta;
    }

    /**
     * Returns the interval statistics between {@code earlier} and this snapshot.
     *
     * @throws IllegalArgumentException if {@code earlier} belongs to a different generation or was
     *     taken after this snapshot
     */
    public CacheStats deltaSince(Snapshot earlier) {
      checkNotNull(earlier);
      checkArgument(
          earlier.generation == generation,
          "Cannot subtract snapshots from different generations: %s vs %s",
          earlier.generation,
          generation);
      checkArgument(
          earlier.sequence <= sequence,
          "Cannot subtract a later snapshot from an earlier one: %s vs %s",
          earlier.sequence,
          sequence);
      return cumulative.minus(earlier.cumulative);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("generation", generation)
          .add("sequence", sequence)
          .add("delta", delta)
          .add("cumulative", cumulative)
          .toString();
    }
  }
}
