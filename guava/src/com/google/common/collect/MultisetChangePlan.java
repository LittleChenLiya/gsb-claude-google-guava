/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.CollectPreconditions.checkNonnegative;

import com.google.common.annotations.GwtCompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An explicit, batch change plan for applying a sequence of count mutations to a {@link
 * Multiset} atomically.
 *
 * <p>A change plan collects {@link #add(Object, int) add}, {@link #setCount(Object, int)
 * setCount}, and {@link #remove(Object, int) remove} operations, each optionally bound to a
 * precondition on the element's original count (the count the element had before the plan was
 * applied). When {@linkplain #apply(Multiset) applied}, the plan first validates every operation
 * (rejecting negative counts and integer overflow up front) and checks every precondition against
 * the original counts. Only if all checks pass does it apply the changes, so a failure in any
 * operation leaves the target multiset completely unchanged.
 *
 * <p>Operations identify their target element by {@link Object#equals} identity; hash collisions
 * are never treated as element equality. Counts are applied via {@link Multiset#setCount(Object,
 * int) setCount} rather than by repeated single-element inserts, so even very large counts are
 * applied without iterating.
 *
 * @author Guava Authors
 * @since 33.0
 */
@GwtCompatible
public final class MultisetChangePlan<E extends @Nullable Object> {

  private final List<Change<E>> changes = new ArrayList<>();

  private MultisetChangePlan() {}

  /** Returns a new, empty change plan. */
  public static <E extends @Nullable Object> MultisetChangePlan<E> create() {
    return new MultisetChangePlan<>();
  }

  // ---- add operations ----

  /**
   * Adds a number of occurrences of an element to this plan, with no precondition.
   *
   * @param element the element to add occurrences of
   * @param occurrences the number of occurrences to add; must be non-negative
   * @return this plan
   * @throws IllegalArgumentException if {@code occurrences} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> add(@ParametricNullness E element, int occurrences) {
    return add(element, occurrences, Change.NO_PRECONDITION);
  }

  /**
   * Adds a number of occurrences of an element to this plan, with a precondition on the original
   * count.
   *
   * @param element the element to add occurrences of
   * @param occurrences the number of occurrences to add; must be non-negative
   * @param expectedCount the count the element is expected to have before the plan is applied
   * @return this plan
   * @throws IllegalArgumentException if {@code occurrences} or {@code expectedCount} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> add(
      @ParametricNullness E element, int occurrences, int expectedCount) {
    checkNonnegative(occurrences, "occurrences");
    if (expectedCount != Change.NO_PRECONDITION) {
      checkNonnegative(expectedCount, "expectedCount");
    }
    changes.add(new Change<>(Change.Type.ADD, element, occurrences, expectedCount));
    return this;
  }

  // ---- setCount operations ----

  /**
   * Sets the count of an element to a new value in this plan, with no precondition.
   *
   * @param element the element to set the count of
   * @param count the desired count; must be non-negative
   * @return this plan
   * @throws IllegalArgumentException if {@code count} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> setCount(@ParametricNullness E element, int count) {
    return setCount(element, Change.NO_PRECONDITION, count);
  }

  /**
   * Sets the count of an element to a new value in this plan, with a precondition on the original
   * count.
   *
   * @param element the element to set the count of
   * @param expectedCount the count the element is expected to have before the plan is applied
   * @param newCount the desired count; must be non-negative
   * @return this plan
   * @throws IllegalArgumentException if {@code expectedCount} or {@code newCount} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> setCount(
      @ParametricNullness E element, int expectedCount, int newCount) {
    if (expectedCount != Change.NO_PRECONDITION) {
      checkNonnegative(expectedCount, "expectedCount");
    }
    checkNonnegative(newCount, "newCount");
    changes.add(new Change<>(Change.Type.SET_COUNT, element, newCount, expectedCount));
    return this;
  }

  // ---- remove operations ----

  /**
   * Removes a number of occurrences of an element from this plan, with no precondition.
   *
   * @param element the element to remove occurrences of
   * @param occurrences the number of occurrences to remove; must be non-negative
   * @return this plan
   * @throws IllegalArgumentException if {@code occurrences} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> remove(@ParametricNullness E element, int occurrences) {
    return remove(element, occurrences, Change.NO_PRECONDITION);
  }

  /**
   * Removes a number of occurrences of an element from this plan, with a precondition on the
   * original count.
   *
   * @param element the element to remove occurrences of
   * @param occurrences the number of occurrences to remove; must be non-negative
   * @param expectedCount the count the element is expected to have before the plan is applied
   * @return this plan
   * @throws IllegalArgumentException if {@code occurrences} or {@code expectedCount} is negative
   */
  @CanIgnoreReturnValue
  public MultisetChangePlan<E> remove(
      @ParametricNullness E element, int occurrences, int expectedCount) {
    checkNonnegative(occurrences, "occurrences");
    if (expectedCount != Change.NO_PRECONDITION) {
      checkNonnegative(expectedCount, "expectedCount");
    }
    changes.add(new Change<>(Change.Type.REMOVE, element, occurrences, expectedCount));
    return this;
  }

  /**
   * Applies all of the operations in this plan to the given multiset, atomically.
   *
   * <p>Every operation is validated first: negative counts and integer overflow are rejected, and
   * each precondition is checked against the element's original count. If any check fails, an
   * exception is thrown and the multiset is left unchanged. Otherwise the changes are applied in
   * order, and the count of each element before the corresponding operation is returned.
   *
   * @param multiset the multiset to apply the plan to
   * @return a list of previous counts, one per operation in plan order
   * @throws NullPointerException if {@code multiset} is null
   * @throws IllegalArgumentException if any operation has a negative count or would cause integer
   *     overflow
   * @throws IllegalStateException if any precondition fails
   */
  @CanIgnoreReturnValue
  public List<Integer> apply(Multiset<E> multiset) {
    checkNotNull(multiset, "multiset");

    int size = changes.size();
    List<Integer> previousCounts = new ArrayList<>(size);

    // Working state: element -> current count, built up as we process operations. We use a
    // HashMap (which honors Object.equals, not just hashCode) so hash collisions are handled
    // correctly and never conflate distinct elements.
    Map<E, Integer> working = new HashMap<>();

    // First pass: validate preconditions against original counts and compute the final state.
    for (Change<E> change : changes) {
      E element = change.element;
      int originalCount = multiset.count(element);

      // Check the precondition against the original count.
      if (change.expectedCount != Change.NO_PRECONDITION) {
        checkState(
            originalCount == change.expectedCount,
            "precondition failed for element %s: expected count %s but was %s",
            element,
            change.expectedCount,
            originalCount);
      }

      // The count this operation observes (original count, or the result of earlier operations).
      int currentCount = working.getOrDefault(element, originalCount);

      int newCount;
      switch (change.type) {
        case ADD:
          newCount = currentCount + change.count;
          // Detect overflow: if newCount wrapped around to a value less than currentCount.
          checkArgument(
              newCount >= currentCount,
              "too many occurrences: adding %s to %s would overflow int",
              change.count,
              currentCount);
          break;
        case SET_COUNT:
          newCount = change.count;
          break;
        case REMOVE:
          newCount = currentCount - change.count;
          checkArgument(
              newCount >= 0,
              "cannot remove %s occurrences of element %s: only %s present",
              change.count,
              element,
              currentCount);
          break;
        default:
          throw new AssertionError("unknown change type: " + change.type);
      }

      working.put(element, newCount);
      previousCounts.add(currentCount);
    }

    // Second pass: apply the final state to the multiset via setCount (not repeated inserts),
    // so even very large counts are applied without iterating.
    for (Map.Entry<E, Integer> entry : working.entrySet()) {
      multiset.setCount(entry.getKey(), entry.getValue());
    }

    return previousCounts;
  }

  /** A single count mutation in the plan. */
  private static final class Change<E extends @Nullable Object> {
    static final int NO_PRECONDITION = -1;

    enum Type {
      ADD,
      SET_COUNT,
      REMOVE
    }

    final Type type;
    final @ParametricNullness E element;
    final int count;
    final int expectedCount;

    Change(Type type, @ParametricNullness E element, int count, int expectedCount) {
      this.type = type;
      this.element = element;
      this.count = count;
      this.expectedCount = expectedCount;
    }
  }
}