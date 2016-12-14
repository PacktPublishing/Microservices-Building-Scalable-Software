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
package org.apache.aurora.scheduler.base;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.apache.aurora.common.application.Lifecycle;
import org.apache.aurora.common.stats.Stats;
import org.slf4j.Logger;

import static java.util.Objects.requireNonNull;

/**
 * Utility class for facilitating async scheduling.
 */
public final class AsyncUtil {

  private AsyncUtil() {
    // Utility class.
  }

  private static final AtomicLong UNCAUGHT_EXCEPTIONS =
      Stats.exportLong("async_executor_uncaught_exceptions");

  /**
   * Creates a {@link ScheduledThreadPoolExecutor} that logs unhandled errors.
   *
   * @param poolSize Thread pool size.
   * @param nameFormat Thread naming format.
   * @param logger Logger instance.
   * @return instance of {@link ScheduledThreadPoolExecutor} enabled to log unhandled exceptions.
   */
  public static ScheduledThreadPoolExecutor loggingScheduledExecutor(
      int poolSize,
      String nameFormat,
      final Logger logger) {

    requireNonNull(nameFormat);

    return new ScheduledThreadPoolExecutor(
        poolSize,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat(nameFormat).build()) {
          @Override
          protected void afterExecute(Runnable runnable, Throwable throwable) {
            super.afterExecute(runnable, throwable);
            evaluateResult(runnable, throwable, logger);
          }
        };
  }

  /**
   * Creates a single-threaded {@link ScheduledThreadPoolExecutor} that logs unhandled errors.
   *
   * @param nameFormat Thread naming format.
   * @param logger Logger instance.
   * @return instance of {@link ScheduledThreadPoolExecutor} enabled to log unhandled exceptions.
   */
  public static ScheduledThreadPoolExecutor singleThreadLoggingScheduledExecutor(
      String nameFormat,
      Logger logger) {

    return loggingScheduledExecutor(1, nameFormat, logger);
  }

  /**
   * Creates a {@link ThreadPoolExecutor} that logs unhandled errors.
   *
   * @param corePoolSize see {@link ThreadPoolExecutor}.
   * @param maxPoolSize see {@link ThreadPoolExecutor}.
   * @param workQueue see {@link ThreadPoolExecutor}.
   * @param nameFormat Thread naming format.
   * @param logger Logger instance.
   * @return instance of {@link ThreadPoolExecutor} enabled to log unhandled exceptions.
   */
  public static ThreadPoolExecutor loggingExecutor(
      int corePoolSize,
      int maxPoolSize,
      BlockingQueue<Runnable> workQueue,
      String nameFormat,
      final Logger logger) {

    return new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        0L,
        TimeUnit.MILLISECONDS,
        workQueue,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat(nameFormat).build()) {
          @Override
          protected void afterExecute(Runnable runnable, Throwable throwable) {
            super.afterExecute(runnable, throwable);
            evaluateResult(runnable, throwable, logger);
          }
        };
  }

  /**
   * Helper wrapper to call the provided {@link Lifecycle} on unhandled error.
   *
   * @param lifecycle {@link Lifecycle} instance.
   * @param logger Logger instance.
   * @param message message to log.
   * @param runnable {@link Runnable} to wrap.
   * @return A new {@link Runnable} logging an error and calling {@link Lifecycle#shutdown()}.
   */
  public static Runnable shutdownOnError(
      Lifecycle lifecycle,
      Logger logger,
      String message,
      Runnable runnable) {

    requireNonNull(lifecycle);
    requireNonNull(logger);
    requireNonNull(message);
    requireNonNull(runnable);

    return () -> {
      try {
        runnable.run();
      } catch (Throwable t) {
        logger.error(message, t);
        lifecycle.shutdown();
      }
    };
  }

  private static void evaluateResult(Runnable runnable, Throwable throwable, Logger logger) {
    // See java.util.concurrent.ThreadPoolExecutor#afterExecute(Runnable, Throwable)
    // for more details and an implementation example.
    if (throwable == null) {
      if (runnable instanceof Future) {
        try {
          Future<?> future = (Future<?>) runnable;
          if (future.isDone()) {
            future.get();
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } catch (ExecutionException ee) {
          logger.error(ee.toString(), ee);
          UNCAUGHT_EXCEPTIONS.incrementAndGet();
        }
      }
    } else {
      logger.error(throwable.toString(), throwable);
      UNCAUGHT_EXCEPTIONS.incrementAndGet();
    }
  }
}
