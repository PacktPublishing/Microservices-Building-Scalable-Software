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
package org.apache.aurora.scheduler.reconciliation;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AbstractIdleService;

import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.mesos.Driver;
import org.apache.aurora.scheduler.reconciliation.ReconciliationModule.BackgroundWorker;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.mesos.Protos;

import static java.util.Objects.requireNonNull;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.aurora.common.quantity.Time.MINUTES;

/**
 * A task reconciler that periodically triggers Mesos (implicit) and Aurora (explicit) task
 * reconciliation to synchronize global task states. More on task reconciliation:
 * http://mesos.apache.org/documentation/latest/reconciliation.
 */
public class TaskReconciler extends AbstractIdleService {

  @VisibleForTesting
  static final String EXPLICIT_STAT_NAME = "reconciliation_explicit_runs";

  @VisibleForTesting
  static final String IMPLICIT_STAT_NAME = "reconciliation_implicit_runs";

  private final TaskReconcilerSettings settings;
  private final Storage storage;
  private final Driver driver;
  private final ScheduledExecutorService executor;
  private final AtomicLong explicitRuns;
  private final AtomicLong implicitRuns;

  static class TaskReconcilerSettings {
    private final Amount<Long, Time> explicitInterval;
    private final Amount<Long, Time> implicitInterval;
    private final long explicitDelayMinutes;
    private final long implicitDelayMinutes;

    @VisibleForTesting
    TaskReconcilerSettings(
        Amount<Long, Time> initialDelay,
        Amount<Long, Time> explicitInterval,
        Amount<Long, Time> implicitInterval,
        Amount<Long, Time> scheduleSpread) {

      this.explicitInterval = requireNonNull(explicitInterval);
      this.implicitInterval = requireNonNull(implicitInterval);
      explicitDelayMinutes = requireNonNull(initialDelay).as(MINUTES);
      implicitDelayMinutes = initialDelay.as(MINUTES) + scheduleSpread.as(MINUTES);
      checkArgument(
          explicitDelayMinutes >= 0,
          "Invalid explicit reconciliation delay: " + explicitDelayMinutes);
      checkArgument(
          implicitDelayMinutes >= 0L,
          "Invalid implicit reconciliation delay: " + implicitDelayMinutes);
    }
  }

  @Inject
  TaskReconciler(
      TaskReconcilerSettings settings,
      Storage storage,
      Driver driver,
      @BackgroundWorker ScheduledExecutorService executor,
      StatsProvider stats) {

    this.settings = requireNonNull(settings);
    this.storage = requireNonNull(storage);
    this.driver = requireNonNull(driver);
    this.executor = requireNonNull(executor);
    this.explicitRuns = stats.makeCounter(EXPLICIT_STAT_NAME);
    this.implicitRuns = stats.makeCounter(IMPLICIT_STAT_NAME);
  }

  @Override
  protected void startUp() {
    // Schedule explicit reconciliation.
    executor.scheduleAtFixedRate(
        () -> {
          ImmutableSet<Protos.TaskStatus> active = FluentIterable
              .from(Storage.Util.fetchTasks(
                  storage,
                  Query.unscoped().byStatus(Tasks.SLAVE_ASSIGNED_STATES)))
              .transform(TASK_TO_PROTO)
              .toSet();

          driver.reconcileTasks(active);
          explicitRuns.incrementAndGet();
        },
        settings.explicitDelayMinutes,
        settings.explicitInterval.as(MINUTES),
        MINUTES.getTimeUnit());

    // Schedule implicit reconciliation.
    executor.scheduleAtFixedRate(
        () -> {
          driver.reconcileTasks(ImmutableSet.of());
          implicitRuns.incrementAndGet();
        },
        settings.implicitDelayMinutes,
        settings.implicitInterval.as(MINUTES),
        MINUTES.getTimeUnit());
  }

  @Override
  protected void shutDown() {
    // Nothing to do - await VM shutdown.
  }

  @VisibleForTesting
  static final Function<IScheduledTask, Protos.TaskStatus> TASK_TO_PROTO =
      t -> Protos.TaskStatus.newBuilder()
          // TODO(maxim): State is required by protobuf but ignored by Mesos for reconciliation
          // purposes. This is the artifact of the native API. The new HTTP Mesos API will be
          // accepting task IDs instead. AURORA-1326 tracks solution on the scheduler side.
          // Setting TASK_RUNNING as a safe dummy value here.
          .setState(Protos.TaskState.TASK_RUNNING)
          .setSlaveId(
              Protos.SlaveID.newBuilder().setValue(t.getAssignedTask().getSlaveId()).build())
          .setTaskId(Protos.TaskID.newBuilder().setValue(t.getAssignedTask().getTaskId()).build())
          .build();
}
