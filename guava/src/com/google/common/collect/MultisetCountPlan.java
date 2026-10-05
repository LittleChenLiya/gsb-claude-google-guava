/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtCompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An explicit, validated plan of count changes ({@code add}, {@code setCount} and {@code remove})
 * to apply to a {@link Multiset} as a single atomic batch.
 *
 * <p>Each operation names an element (matched by {@link Object#equals}, so distinct elements that
 * merely share a hash code are never conflated) and carries a <b>precondition on the original
 * count</b> of that element in the source multiset. When the plan is {@linkplain #apply applied}:
 *
 * <ul>
 *   <li>Every operation's expected original count is checked against the source. If any
 *       operation's precondition is wrong (for example, the second operation of a batch), the
 *       whole batch is rejected and the source is left unchanged.
 *   <li>Negative counts and {@code int} overflow are rejected up front: a count that would exceed
 *       {@link Integer#MAX_VALUE} (for example, adding 1 to {@code Integer.MAX_VALUE}) throws
 *       instead of wrapping around to zero or a negative value, and a count that would drop below
 *       zero throws as well.
 *   <li>Counts are written directly into a private working map, never by performing one insertion
 *       per occurrence, so plans involving huge counts (up to {@link Integer#MAX_VALUE}) run in
 *       constant time per operation.
 *   <li>The result is a new {@link ImmutableMultiset}, fully separated from the input: the source
 *       is never mutated, and later changes to the source do not affect the result.
 * </ul>
 *
 * <p>Any failure is reported with the index of the offending operation, as in {@code
 * "operation[1] (SET_COUNT on b): expected original count 3 but found 2"}.
 *
 * <p>Example using all three operation kinds:
 *
 * <pre>{@code
 * MultisetCountPlan<String> plan =
 *     MultisetCountPlan.<String>builder()
 *         .add("apple", 2, 1)       // apple: requires original count 1, becomes 3
 *         .setCount("banana", 5, 0) // banana: requires original count 0, becomes 5
 *         .remove("cherry", 1, 4)   // cherry: requires original count 4, becomes 3
 *         .build();
 * ImmutableMultiset<String> result = plan.apply(source);
 * }</pre>
 *
 * @param <E> the element type of the multisets this plan applies to
 * @since NEXT
 */
@GwtCompatible
public final class MultisetCountPlan<E> {
  /** The kind of count change a single operation describes. */
  private enum Op {
    ADD,
    SET_COUNT,
    REMOVE
  }

  private static final class Operation<E> {
    final Op op;
    final E element;
    final int amount;
    final int expectedOriginalCount;

    Operation(Op op, E element, int amount, int expectedOriginalCount) {
      this.op = op;
      this.element = element;
      this.amount = amount;
      this.expectedOriginalCount = expectedOriginalCount;
    }
  }

  private final List<Operation<E>> operations;

  private MultisetCountPlan(List<Operation<E>> operations) {
    this.operations = operations;
  }

  /** Returns a new builder for a plan with no operations. */
  public static <E> Builder<E> builder() {
    return new Builder<>();
  }

  /**
   * Validates this plan against {@code source} and returns the resulting multiset.
   *
   * <p>The source is never modified. All validation (original-count preconditions, nonnegative
   * counts and overflow checks) happens against a private working copy, so if any operation fails,
   * the batch has no observable effect at all.
   *
   * @throws IllegalArgumentException if any operation's expected original count does not match the
   *     source, if a resulting count would become negative, or if a resulting count would exceed
   *     {@link Integer#MAX_VALUE}
   */
  public ImmutableMultiset<E> apply(Multiset<E> source) {
    checkNotNull(source);
    // Work on a private copy of the counts. If any operation of the batch fails, we simply throw
    // away this map: the source is untouched and no partial result is ever published.
    LinkedHashMap<E, Integer> counts = new LinkedHashMap<>();
    for (Multiset.Entry<E> entry : source.entrySet()) {
      counts.put(entry.getElement(), entry.getCount());
    }
    for (int i = 0; i < operations.size(); i++) {
      Operation<E> operation = operations.get(i);
      int original = source.count(operation.element);
      checkArgument(
          original == operation.expectedOriginalCount,
          "operation[%s] (%s on %s): expected original count %s but found %s",
          i,
          operation.op,
          operation.element,
          operation.expectedOriginalCount,
          original);
      Integer currentBoxed = counts.get(operation.element);
      int current = (currentBoxed == null) ? 0 : currentBoxed;
      long updated;
      switch (operation.op) {
        case ADD:
          updated = (long) current + operation.amount;
          break;
        case SET_COUNT:
          updated = operation.amount;
          break;
        case REMOVE:
          updated = (long) current - operation.amount;
          break;
        default:
          throw new AssertionError(operation.op);
      }
      checkArgument(
          updated >= 0,
          "operation[%s] (%s on %s): count would become negative (%s)",
          i,
          operation.op,
          operation.element,
          updated);
      checkArgument(
          updated <= Integer.MAX_VALUE,
          "operation[%s] (%s on %s): count would overflow int (%s)",
          i,
          operation.op,
          operation.element,
          updated);
      // Write the new count directly; we never loop performing one insert per occurrence.
      if (updated == 0) {
        counts.remove(operation.element);
      } else {
        counts.put(operation.element, (int) updated);
      }
    }
    ImmutableMultiset.Builder<E> builder = ImmutableMultiset.builder();
    for (Map.Entry<E, Integer> entry : counts.entrySet()) {
      builder.setCount(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  /** Builder for {@link MultisetCountPlan}. */
  public static final class Builder<E> {
    private final List<Operation<E>> operations = new ArrayList<>();

    private Builder() {}

    /**
     * Adds an operation that adds {@code occurrences} occurrences of {@code element}, requiring
     * that the element's original count in the source equals {@code expectedOriginalCount}.
     *
     * @throws IllegalArgumentException if either count is negative
     */
    @CanIgnoreReturnValue
    public Builder<E> add(E element, int occurrences, int expectedOriginalCount) {
      return enqueue(Op.ADD, element, occurrences, expectedOriginalCount);
    }

    /**
     * Adds an operation that sets the count of {@code element} to {@code count}, requiring that
     * the element's original count in the source equals {@code expectedOriginalCount}.
     *
     * @throws IllegalArgumentException if either count is negative
     */
    @CanIgnoreReturnValue
    public Builder<E> setCount(E element, int count, int expectedOriginalCount) {
      return enqueue(Op.SET_COUNT, element, count, expectedOriginalCount);
    }

    /**
     * Adds an operation that removes {@code occurrences} occurrences of {@code element},
     * requiring that the element's original count in the source equals {@code
     * expectedOriginalCount}.
     *
     * @throws IllegalArgumentException if either count is negative
     */
    @CanIgnoreReturnValue
    public Builder<E> remove(E element, int occurrences, int expectedOriginalCount) {
      return enqueue(Op.REMOVE, element, occurrences, expectedOriginalCount);
    }

    @CanIgnoreReturnValue
    private Builder<E> enqueue(Op op, E element, int amount, int expectedOriginalCount) {
      checkNotNull(element);
      checkArgument(
          amount >= 0,
          "operation[%s] (%s): amount must be nonnegative: %s",
          operations.size(),
          op,
          amount);
      checkArgument(
          expectedOriginalCount >= 0,
          "operation[%s] (%s): expected original count must be nonnegative: %s",
          operations.size(),
          op,
          expectedOriginalCount);
      operations.add(new Operation<>(op, element, amount, expectedOriginalCount));
      return this;
    }

    /** Returns an immutable plan containing the operations added so far, in order. */
    public MultisetCountPlan<E> build() {
      return new MultisetCountPlan<>(new ArrayList<>(operations));
    }
  }
}
