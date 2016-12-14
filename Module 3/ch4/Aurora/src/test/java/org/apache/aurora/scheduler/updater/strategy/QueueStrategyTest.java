/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.updater.strategy;

import java.util.Set;

import com.google.common.collect.Ordering;

import org.junit.Test;

import static com.google.common.collect.ImmutableSet.of;

import static org.junit.Assert.assertEquals;

public class QueueStrategyTest {

  private static final Ordering<Integer> ORDERING = Ordering.natural();
  private static final Set<Integer> EMPTY = of();

  @Test(expected = IllegalArgumentException.class)
  public void testBadParameter() {
    new QueueStrategy<Integer>(ORDERING, 0);
  }

  @Test
  public void testNoWorkToDo() {
    UpdateStrategy<Integer> strategy = new QueueStrategy<>(ORDERING, 2);
    assertEquals(EMPTY, strategy.getNextGroup(EMPTY, of(0, 1)));
    assertEquals(EMPTY, strategy.getNextGroup(EMPTY, EMPTY));
  }

  @Test
  public void testFillsQueue() {
    UpdateStrategy<Integer> strategy = new QueueStrategy<>(ORDERING, 3);
    assertEquals(of(0, 1, 2), strategy.getNextGroup(of(0, 1, 2, 3, 4, 5), EMPTY));
    assertEquals(of(2), strategy.getNextGroup(of(2, 3, 4), of(0, 1)));
  }

  @Test
  public void testNonContiguous() {
    UpdateStrategy<Integer> strategy = new QueueStrategy<>(ORDERING, 3);
    assertEquals(of(0, 1, 3), strategy.getNextGroup(of(0, 1, 3, 5, 8), EMPTY));
    assertEquals(of(5, 8), strategy.getNextGroup(of(5, 8), of(3)));
  }

  @Test
  public void testExhausted() {
    UpdateStrategy<Integer> strategy = new QueueStrategy<>(ORDERING, 3);
    assertEquals(of(0, 1, 2), strategy.getNextGroup(of(0, 1, 2), EMPTY));
    assertEquals(of(0, 1), strategy.getNextGroup(of(0, 1), EMPTY));
    assertEquals(of(1), strategy.getNextGroup(of(1), EMPTY));
  }

  @Test
  public void testActiveTooLarge() {
    UpdateStrategy<Integer> strategy = new QueueStrategy<>(ORDERING, 2);
    assertEquals(EMPTY, strategy.getNextGroup(of(0, 1, 2), of(3, 4, 5)));
  }
}
