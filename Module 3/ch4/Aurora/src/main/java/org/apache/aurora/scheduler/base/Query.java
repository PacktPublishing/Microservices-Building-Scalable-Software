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

import java.util.EnumSet;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;

import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.TaskQuery;
import org.apache.aurora.scheduler.storage.entities.IInstanceKey;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.ITaskQuery;

import static java.util.Objects.requireNonNull;

/**
 * A utility class to construct storage queries.
 * TODO(Sathya): Add some basic unit tests for isJobScoped and isOnlyJobScoped.
 */
public final class Query {

  private Query() {
    // Utility.
  }

  /**
   * Checks whether a query is scoped to a specific job.
   * A query scoped to a job specifies either:
   * <ol>
   *   <li>a role, environment and job name, or</li>
   *   <li>a set of JobKey instances</li>
   * </ol>
   *
   * @param taskQuery Query to test.
   * @return {@code true} if the query specifies at least one job key, otherwise {@code false}.
   */
  public static boolean isJobScoped(Builder taskQuery) {
    ITaskQuery q = taskQuery.get();
    return q.isSetRole() && q.isSetEnvironment() && q.isSetJobName() || !q.getJobKeys().isEmpty();
  }

  public static Builder arbitrary(TaskQuery query) {
    return new Builder(query.deepCopy());
  }

  public static Builder unscoped() {
    return new Builder();
  }

  public static Builder roleScoped(String role) {
    return unscoped().byRole(role);
  }

  public static Builder envScoped(String role, String environment) {
    return unscoped().byEnv(role, environment);
  }

  public static Builder jobScoped(IJobKey jobKey) {
    return unscoped().byJob(jobKey);
  }

  public static Builder jobScoped(Iterable<IJobKey> jobKeys) {
    return unscoped().byJobKeys(jobKeys);
  }

  public static Builder instanceScoped(IInstanceKey instanceKey) {
    return instanceScoped(instanceKey.getJobKey(), instanceKey.getInstanceId());
  }

  public static Builder instanceScoped(IJobKey jobKey, int instanceId, int... instanceIds) {
    return unscoped().byInstances(jobKey, instanceId, instanceIds);
  }

  public static Builder instanceScoped(IJobKey jobKey, Iterable<Integer> instanceIds) {
    return unscoped().byInstances(jobKey, instanceIds);
  }

  public static Builder taskScoped(String taskId, String... taskIds) {
    return unscoped().byId(taskId, taskIds);
  }

  public static Builder taskScoped(Iterable<String> taskIds) {
    return unscoped().byId(taskIds);
  }

  public static Builder slaveScoped(String slaveHost, String... slaveHosts) {
    return unscoped().bySlave(slaveHost, slaveHosts);
  }

  public static Builder slaveScoped(Iterable<String> slaveHosts) {
    return unscoped().bySlave(slaveHosts);
  }

  public static Builder statusScoped(ScheduleStatus status, ScheduleStatus... statuses) {
    return unscoped().byStatus(status, statuses);
  }

  /**
   * A Builder of TaskQueries. Builders are immutable and provide access to a set of convenience
   * methods to return a new builder of another scope. Available scope filters include slave,
   * taskId, role, jobs of a role, and instances of a job.
   *
   * <p>
   * This class does not expose the full functionality of TaskQuery but rather subsets of it that
   * can be efficiently executed and make sense in the context of the scheduler datastores. This
   * builder should be preferred over constructing TaskQueries directly.
   * </p>
   *
   * TODO(ksweeney): Add an environment scope.
   */
  public static final class Builder implements Supplier<ITaskQuery> {
    private final TaskQuery query;

    Builder() {
      this(new TaskQuery());
    }

    Builder(TaskQuery query) {
      // It is expected that the caller calls deepCopy.
      this.query = query;
    }

    /**
     * Build a query that is the combination of all the filters applied to a Builder. Mutating the
     * returned object will not affect the state of the builder. Can be called any number of times
     * and will return a new {@code TaskQuery} each time.
     *
     * @return A new TaskQuery satisfying this builder's constraints.
     */
    @Override
    public ITaskQuery get() {
      return ITaskQuery.build(query);
    }

    @Override
    public boolean equals(Object that) {
      return that instanceof Builder && get().equals(((Builder) that).get());
    }

    @Override
    public int hashCode() {
      return Objects.hash(get());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("query", query)
          .toString();
    }

    /**
     * Create a builder scoped to tasks.
     *
     * @param taskId An ID of a task to scope the builder to.
     * @param taskIds Additional IDs of tasks to scope the builder to (they are ORed together).
     * @return A new Builder scoped to the given tasks.
     */
    public Builder byId(String taskId, String... taskIds) {
      requireNonNull(taskId);

      return new Builder(
          query.deepCopy()
              .setTaskIds(ImmutableSet.<String>builder().add(taskId).add(taskIds).build()));
    }

    /**
     * Create a builder scoped to tasks.
     *
     * @see #byId(String, String...)
     *
     * @param taskIds The IDs of the tasks to scope the query to (ORed together).
     * @return A new Builder scoped to the given tasks.
     */
    public Builder byId(Iterable<String> taskIds) {
      requireNonNull(taskIds);

      return new Builder(
          query.deepCopy().setTaskIds(ImmutableSet.copyOf(taskIds)));
    }

    /**
     * Create a builder scoped to a role. A role scope conflicts with job and instance scopes.
     *
     * @param role The role to scope the query to.
     * @return A new Builder scoped to the given role.
     */
    public Builder byRole(String role) {
      requireNonNull(role);

      return new Builder(query.deepCopy().setRole(role));
    }

    /**
     * Create a builder scoped to an environment. An environment scope conflicts with role, job,
     * and instance scopes.
     *
     * @param role The role to scope the query to.
     * @param environment The environment to scope the query to.
     * @return A new Builder scoped to the given environment.
     */
    public Builder byEnv(String role, String environment) {
      requireNonNull(role);
      requireNonNull(environment);

      return new Builder(
          query.deepCopy().setRole(role).setEnvironment(environment));
    }

    /**
     * Returns a new builder scoped to the job uniquely identified by the given key. A job scope
     * conflicts with role and instance scopes.
     *
     * @param jobKey The key of the job to scope the query to.
     * @return A new Builder scoped to the given jobKey.
     */
    public Builder byJob(IJobKey jobKey) {
      JobKeys.assertValid(jobKey);

      return new Builder(
          query.deepCopy()
              .setJobKeys(ImmutableSet.of(jobKey.newBuilder())));
    }

    /**
     * Returns a new builder scoped to the slaves uniquely identified by the given slaveHosts. A
     * builder can only be scoped to slaves once.
     *
     * @param slaveHost The hostname of the slave to scope the query to.
     * @param slaveHosts Additional slave hostnames to scope this query to (they are ORed together).
     * @return A new Builder scoped to the given slaves.
     */
    public Builder bySlave(String slaveHost, String... slaveHosts) {
      requireNonNull(slaveHost);

      return bySlave(ImmutableSet.<String>builder().add(slaveHost).add(slaveHosts).build());
    }

    /**
     * Creates a new builder scoped to slaveHosts.
     *
     * @see Builder#bySlave(String, String...)
     *
     * @param slaveHosts The slaveHost to scope this builder to.
     * @return A new Builder scoped to the slaveHosts.
     */
    public Builder bySlave(Iterable<String> slaveHosts) {
      requireNonNull(slaveHosts);

      return new Builder(query.deepCopy().setSlaveHosts(ImmutableSet.copyOf(slaveHosts)));
    }

    /**
     * Returns a new builder scoped to the given statuses. A builder can only be scoped to statuses
     * once.
     *
     * @param status The status to scope this Builder to.
     * @param statuses Additional statuses to scope this Builder to (they are ORed together).
     * @return A new Builder scoped to the given statuses.
     */
    public Builder byStatus(ScheduleStatus status, ScheduleStatus... statuses) {
      requireNonNull(status);

      return new Builder(
          query.deepCopy().setStatuses(EnumSet.of(status, statuses)));
    }

    /**
     * Create a new Builder scoped to statuses.
     *
     * @see Builder#byStatus(ScheduleStatus, ScheduleStatus...)
     *
     * @param statuses The statuses to scope this Builder to.
     * @return A new Builder scoped to the given statuses.
     */
    public Builder byStatus(Iterable<ScheduleStatus> statuses) {
      requireNonNull(statuses);

      return new Builder(
          query.deepCopy().setStatuses(EnumSet.copyOf(ImmutableSet.copyOf(statuses))));
    }

    /**
     * Returns a new Builder scoped to the given instances of the given job. A builder can only
     * be scoped to a set of instances, a job, or a role once.
     *
     * @param jobKey The key identifying the job.
     * @param instanceId An instance id of the target job.
     * @param instanceIds Additional instance ids of the target job.
     * @return A new Builder scoped to the given instance ids.
     */
    public Builder byInstances(IJobKey jobKey, int instanceId, int... instanceIds) {
      JobKeys.assertValid(jobKey);

      return new Builder(
          query.deepCopy()
              .setRole(jobKey.getRole())
              .setEnvironment(jobKey.getEnvironment())
              .setJobName(jobKey.getName())
              .setInstanceIds(ImmutableSet.<Integer>builder()
                  .add(instanceId)
                  .addAll(Ints.asList(instanceIds))
                  .build()));
    }

    /**
     * Create a new Builder scoped to instances.
     *
     * @see Builder#byInstances
     *
     * @param jobKey The key identifying the job.
     * @param instanceIds Instances of the target job.
     * @return A new Builder scoped to the given instance ids.
     */
    public Builder byInstances(IJobKey jobKey, Iterable<Integer> instanceIds) {
      JobKeys.assertValid(jobKey);
      requireNonNull(instanceIds);

      return new Builder(
          query.deepCopy()
              .setRole(jobKey.getRole())
              .setEnvironment(jobKey.getEnvironment())
              .setJobName(jobKey.getName())
              .setInstanceIds(ImmutableSet.copyOf(instanceIds)));
    }

    /**
     * Returns a new builder scoped to the jobs uniquely identified by the given jobKeys. A
     * builder can only be scoped to jobs once.
     *
     * @param jobKeys The job keys to scope this builder to.
     * @return A new Builder scoped to the job keys.
     */
    public Builder byJobKeys(Iterable<IJobKey> jobKeys) {
      requireNonNull(jobKeys);

      return new Builder(query.deepCopy().setJobKeys(IJobKey.toBuildersSet(jobKeys)));
    }

    /**
     * A convenience method to scope this builder to {@link Tasks#ACTIVE_STATES}.
     *
     * @return A new Builder scoped to Tasks#ACTIVE_STATES.
     */
    public Builder active() {
      return byStatus(Tasks.ACTIVE_STATES);
    }

    /**
     * A convenience method to scope this builder to {@link Tasks#TERMINAL_STATES}.
     *
     * @return A new Builder scoped to Tasks#TERMINAL_STATES.
     */
    public Builder terminal() {
      return byStatus(Tasks.TERMINAL_STATES);
    }
  }
}
