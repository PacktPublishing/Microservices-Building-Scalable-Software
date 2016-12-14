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
package org.apache.aurora.scheduler.scheduling;

import java.util.concurrent.ConcurrentMap;

import javax.inject.Inject;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.RateLimiter;

import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.stats.SlidingStats;
import org.apache.aurora.common.util.BackoffStrategy;
import org.apache.aurora.scheduler.async.AsyncModule.AsyncExecutor;
import org.apache.aurora.scheduler.async.DelayExecutor;
import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.events.PubsubEvent.EventSubscriber;
import org.apache.aurora.scheduler.events.PubsubEvent.TaskStateChange;
import org.apache.aurora.scheduler.events.PubsubEvent.TasksDeleted;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.gen.ScheduleStatus.PENDING;

/**
 * A collection of task groups, where a task group is a collection of tasks that are known to be
 * equal in the way they schedule. This is expected to be tasks associated with the same job key,
 * who also have {@code equal()} {@link org.apache.aurora.scheduler.storage.entities.ITaskConfig}
 * values.
 * <p>
 * This is used to prevent redundant work in trying to schedule tasks as well as to provide
 * nearly-equal responsiveness when scheduling across jobs.  In other words, a 1000 instance job
 * cannot starve a 1 instance job.
 */
public class TaskGroups implements EventSubscriber {

  private final ConcurrentMap<TaskGroupKey, TaskGroup> groups = Maps.newConcurrentMap();
  private final DelayExecutor executor;
  private final TaskScheduler taskScheduler;
  private final long firstScheduleDelay;
  private final BackoffStrategy backoff;
  private final RescheduleCalculator rescheduleCalculator;

  // Track the penalties of tasks at the time they were scheduled. This is to provide data that
  // may influence the selection of a different backoff strategy.
  private final SlidingStats scheduledTaskPenalties =
      new SlidingStats("scheduled_task_penalty", "ms");

  public static class TaskGroupsSettings {
    private final Amount<Long, Time> firstScheduleDelay;
    private final BackoffStrategy taskGroupBackoff;
    private final RateLimiter rateLimiter;

    public TaskGroupsSettings(
        Amount<Long, Time> firstScheduleDelay,
        BackoffStrategy taskGroupBackoff,
        RateLimiter rateLimiter) {

      this.firstScheduleDelay = requireNonNull(firstScheduleDelay);
      this.taskGroupBackoff = requireNonNull(taskGroupBackoff);
      this.rateLimiter = requireNonNull(rateLimiter);
    }
  }

  @Inject
  TaskGroups(
      @AsyncExecutor DelayExecutor executor,
      TaskGroupsSettings settings,
      TaskScheduler taskScheduler,
      RescheduleCalculator rescheduleCalculator) {

    requireNonNull(settings.firstScheduleDelay);
    Preconditions.checkArgument(settings.firstScheduleDelay.getValue() > 0);

    this.executor = requireNonNull(executor);
    requireNonNull(settings.rateLimiter);
    requireNonNull(taskScheduler);
    this.firstScheduleDelay = settings.firstScheduleDelay.as(Time.MILLISECONDS);
    this.backoff = requireNonNull(settings.taskGroupBackoff);
    this.rescheduleCalculator = requireNonNull(rescheduleCalculator);

    this.taskScheduler = taskId -> {
      settings.rateLimiter.acquire();
      return taskScheduler.schedule(taskId);
    };
  }

  private synchronized void evaluateGroupLater(Runnable evaluate, TaskGroup group) {
    // Avoid check-then-act by holding the intrinsic lock.  If not done atomically, we could
    // remove a group while a task is being added to it.
    if (group.hasMore()) {
      executor.execute(evaluate, Amount.of(group.getPenaltyMs(), Time.MILLISECONDS));
    } else {
      groups.remove(group.getKey());
    }
  }

  private void startGroup(final TaskGroup group) {
    Runnable monitor = new Runnable() {
      @Override
      public void run() {
        Optional<String> taskId = group.peek();
        long penaltyMs = 0;
        if (taskId.isPresent()) {
          if (taskScheduler.schedule(taskId.get())) {
            scheduledTaskPenalties.accumulate(group.getPenaltyMs());
            group.remove(taskId.get());
            if (group.hasMore()) {
              penaltyMs = firstScheduleDelay;
            }
          } else {
            penaltyMs = backoff.calculateBackoffMs(group.getPenaltyMs());
          }
        }

        group.setPenaltyMs(penaltyMs);
        evaluateGroupLater(this, group);
      }
    };
    evaluateGroupLater(monitor, group);
  }

  /**
   * Informs the task groups of a task state change.
   * <p>
   * This is used to observe {@link org.apache.aurora.gen.ScheduleStatus#PENDING} tasks and begin
   * attempting to schedule them.
   *
   * @param stateChange State change notification.
   */
  @Subscribe
  public synchronized void taskChangedState(TaskStateChange stateChange) {
    if (stateChange.getNewState() == PENDING) {
      IScheduledTask task = stateChange.getTask();
      TaskGroupKey key = TaskGroupKey.from(task.getAssignedTask().getTask());
      TaskGroup newGroup = new TaskGroup(key, Tasks.id(task));
      TaskGroup existing = groups.putIfAbsent(key, newGroup);
      if (existing == null) {
        long penaltyMs;
        if (stateChange.isTransition()) {
          penaltyMs = firstScheduleDelay;
        } else {
          penaltyMs = rescheduleCalculator.getStartupScheduleDelayMs(task);
        }
        newGroup.setPenaltyMs(penaltyMs);
        startGroup(newGroup);
      } else {
        existing.offer(Tasks.id(task));
      }
    }
  }

  /**
   * Signals the scheduler that tasks have been deleted.
   *
   * @param deleted Tasks deleted event.
   */
  @Subscribe
  public synchronized void tasksDeleted(TasksDeleted deleted) {
    for (IAssignedTask task
        : Iterables.transform(deleted.getTasks(), IScheduledTask::getAssignedTask)) {
      TaskGroup group = groups.get(TaskGroupKey.from(task.getTask()));
      if (group != null) {
        group.remove(task.getTaskId());
      }
    }
  }

  public Iterable<TaskGroup> getGroups() {
    return ImmutableSet.copyOf(groups.values());
  }

}
