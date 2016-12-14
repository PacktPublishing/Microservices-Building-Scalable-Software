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
package org.apache.aurora.common.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.apache.aurora.common.base.ExceptionalSupplier;
import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility for dealing with backoffs of retryable actions.
 *
 * <p>TODO(John Sirois): investigate synergies with BackoffDecider.
 *
 * @author John Sirois
 */
public class BackoffHelper {
  private static final Logger LOG = LoggerFactory.getLogger(BackoffHelper.class);

  private static final Amount<Long,Time> DEFAULT_INITIAL_BACKOFF = Amount.of(1L, Time.SECONDS);
  private static final Amount<Long,Time> DEFAULT_MAX_BACKOFF = Amount.of(1L, Time.MINUTES);

  private final Clock clock;
  private final BackoffStrategy backoffStrategy;

  /**
   * Creates a new BackoffHelper that uses truncated binary backoff starting at a 1 second backoff
   * and maxing out at a 1 minute backoff.
   */
  public BackoffHelper() {
    this(DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
  }

  /**
   * Creates a new BackoffHelper that uses truncated binary backoff starting at the given
   * {@code initialBackoff} and maxing out at the given {@code maxBackoff}.
   *
   * @param initialBackoff the initial amount of time to back off
   * @param maxBackoff the maximum amount of time to back off
   */
  public BackoffHelper(Amount<Long, Time> initialBackoff, Amount<Long, Time> maxBackoff) {
    this(new TruncatedBinaryBackoff(initialBackoff, maxBackoff));
  }

  /**
   * Creates a BackoffHelper that uses the given {@code backoffStrategy} to calculate backoffs
   * between retries.
   *
   * @param backoffStrategy the backoff strategy to use
   */
  public BackoffHelper(BackoffStrategy backoffStrategy) {
    this(Clock.SYSTEM_CLOCK, backoffStrategy);
  }

  @VisibleForTesting BackoffHelper(Clock clock, BackoffStrategy backoffStrategy) {
    this.clock = Preconditions.checkNotNull(clock);
    this.backoffStrategy = Preconditions.checkNotNull(backoffStrategy);
  }

  /**
   * Executes the given task using the configured backoff strategy until the task succeeds as
   * indicated by returning {@code true}.
   *
   * @param task the retryable task to execute until success
   * @throws InterruptedException if interrupted while waiting for the task to execute successfully
   * @throws BackoffStoppedException if the backoff stopped unsuccessfully
   * @throws E if the task throws
   */
  public <E extends Exception> void doUntilSuccess(final ExceptionalSupplier<Boolean, E> task)
      throws InterruptedException, BackoffStoppedException, E {
    doUntilResult(() -> {
      Boolean result = task.get();
      return Boolean.TRUE.equals(result) ? result : null;
    });
  }

  /**
   * Executes the given task using the configured backoff strategy until the task succeeds as
   * indicated by returning a non-null value.
   *
   * @param task the retryable task to execute until success
   * @return the result of the successfully executed task
   * @throws InterruptedException if interrupted while waiting for the task to execute successfully
   * @throws BackoffStoppedException if the backoff stopped unsuccessfully
   * @throws E if the task throws
   */
  public <T, E extends Exception> T doUntilResult(ExceptionalSupplier<T, E> task)
      throws InterruptedException, BackoffStoppedException, E {
    T result = task.get(); // give an immediate try
    return (result != null) ? result : retryWork(task);
  }

  private <T, E extends Exception> T retryWork(ExceptionalSupplier<T, E> work)
      throws E, InterruptedException, BackoffStoppedException {
    long currentBackoffMs = 0;
    while (backoffStrategy.shouldContinue(currentBackoffMs)) {
      currentBackoffMs = backoffStrategy.calculateBackoffMs(currentBackoffMs);
      LOG.debug("Operation failed, backing off for " + currentBackoffMs + "ms");
      clock.waitFor(currentBackoffMs);

      T result = work.get();
      if (result != null) {
        return result;
      }
    }
    throw new BackoffStoppedException(String.format("Backoff stopped without succeeding."));
  }

  /**
   * Occurs after the backoff strategy should stop.
   */
  public static class BackoffStoppedException extends RuntimeException {
    public BackoffStoppedException(String msg) {
      super(msg);
    }
  }
}
