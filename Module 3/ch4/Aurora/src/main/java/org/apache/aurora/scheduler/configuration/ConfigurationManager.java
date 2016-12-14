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
package org.apache.aurora.scheduler.configuration;

import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;

import org.apache.aurora.gen.Container;
import org.apache.aurora.gen.DockerParameter;
import org.apache.aurora.gen.JobConfiguration;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.gen.TaskConfig._Fields;
import org.apache.aurora.gen.TaskConstraint;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.UserProvidedStrings;
import org.apache.aurora.scheduler.storage.entities.IConstraint;
import org.apache.aurora.scheduler.storage.entities.IContainer;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.entities.ITaskConstraint;
import org.apache.aurora.scheduler.storage.entities.IValueConstraint;

import static java.util.Objects.requireNonNull;

/**
 * Manages translation from a string-mapped configuration to a concrete configuration type, and
 * defaults for optional values.
 *
 * TODO(William Farner): Add input validation to all fields (strings not empty, positive ints, etc).
 */
public class ConfigurationManager {

  public static final String DEDICATED_ATTRIBUTE = "dedicated";

  private interface Validator<T> {
    void validate(T value) throws TaskDescriptionException;
  }

  private static class GreaterThan implements Validator<Number> {
    private final double min;
    private final String label;

    GreaterThan(double min, String label) {
      this.min = min;
      this.label = label;
    }

    @Override
    public void validate(Number value) throws TaskDescriptionException {
      if (this.min >= value.doubleValue()) {
        throw new TaskDescriptionException(label + " must be greater than " + this.min);
      }
    }
  }

  private static class RequiredFieldValidator<T> implements Validator<TaskConfig> {
    private final _Fields field;
    private final Validator<T> validator;

    RequiredFieldValidator(_Fields field, Validator<T> validator) {
      this.field = field;
      this.validator = validator;
    }

    public void validate(TaskConfig task) throws TaskDescriptionException {
      if (!task.isSet(field)) {
        throw new TaskDescriptionException("Field " + field.getFieldName() + " is required.");
      }
      @SuppressWarnings("unchecked")
      T value = (T) task.getFieldValue(field);
      validator.validate(value);
    }
  }

  private static final Iterable<RequiredFieldValidator<?>> REQUIRED_FIELDS_VALIDATORS =
      ImmutableList.of(
          new RequiredFieldValidator<>(_Fields.NUM_CPUS, new GreaterThan(0.0, "num_cpus")),
          new RequiredFieldValidator<>(_Fields.RAM_MB, new GreaterThan(0.0, "ram_mb")),
          new RequiredFieldValidator<>(_Fields.DISK_MB, new GreaterThan(0.0, "disk_mb")));

  public static class ConfigurationManagerSettings {
    private final ImmutableSet<Container._Fields> allowedContainerTypes;
    private final boolean allowDockerParameters;
    private final Multimap<String, String> defaultDockerParameters;
    private final boolean requireDockerUseExecutor;

    public ConfigurationManagerSettings(
        ImmutableSet<Container._Fields> allowedContainerTypes,
        boolean allowDockerParameters,
        Multimap<String, String> defaultDockerParameters,
        boolean requireDockerUseExecutor) {

      this.allowedContainerTypes = requireNonNull(allowedContainerTypes);
      this.allowDockerParameters = allowDockerParameters;
      this.defaultDockerParameters = requireNonNull(defaultDockerParameters);
      this.requireDockerUseExecutor = requireDockerUseExecutor;
    }
  }

  private final ConfigurationManagerSettings settings;
  private final TierManager tierManager;

  @Inject
  public ConfigurationManager(ConfigurationManagerSettings settings, TierManager tierManager) {
    this.settings = requireNonNull(settings);
    this.tierManager = requireNonNull(tierManager);
  }

  private static String getRole(IValueConstraint constraint) {
    return Iterables.getOnlyElement(constraint.getValues()).split("/")[0];
  }

  private static boolean isValueConstraint(ITaskConstraint taskConstraint) {
    return taskConstraint.getSetField() == TaskConstraint._Fields.VALUE;
  }

  public static boolean isDedicated(Iterable<IConstraint> taskConstraints) {
    return Iterables.any(taskConstraints, getConstraintByName(DEDICATED_ATTRIBUTE));
  }

  @Nullable
  private static IConstraint getDedicatedConstraint(ITaskConfig task) {
    return Iterables.find(task.getConstraints(), getConstraintByName(DEDICATED_ATTRIBUTE), null);
  }

  /**
   * Check validity of and populates defaults in a job configuration.  This will return a deep copy
   * of the provided job configuration with default configuration values applied, and configuration
   * map values sanitized and applied to their respective struct fields.
   *
   * @param job Job to validate and populate.
   * @return A deep copy of {@code job} that has been populated.
   * @throws TaskDescriptionException If the job configuration is invalid.
   */
  public IJobConfiguration validateAndPopulate(IJobConfiguration job)
      throws TaskDescriptionException {

    requireNonNull(job);

    if (!job.isSetTaskConfig()) {
      throw new TaskDescriptionException("Job configuration must have taskConfig set.");
    }

    if (job.getInstanceCount() <= 0) {
      throw new TaskDescriptionException("Instance count must be positive.");
    }

    JobConfiguration builder = job.newBuilder();

    if (!JobKeys.isValid(job.getKey())) {
      throw new TaskDescriptionException("Job key " + job.getKey() + " is invalid.");
    }

    if (job.isSetOwner() && !UserProvidedStrings.isGoodIdentifier(job.getOwner().getUser())) {
      throw new TaskDescriptionException(
          "Job user contains illegal characters: " + job.getOwner().getUser());
    }

    builder.setTaskConfig(
        validateAndPopulate(ITaskConfig.build(builder.getTaskConfig())).newBuilder());

    // Only one of [service=true, cron_schedule] may be set.
    if (!Strings.isNullOrEmpty(job.getCronSchedule()) && builder.getTaskConfig().isIsService()) {
      throw new TaskDescriptionException(
          "A service task may not be run on a cron schedule: " + builder);
    }

    return IJobConfiguration.build(builder);
  }

  @VisibleForTesting
  static final String NO_DOCKER_PARAMETERS =
      "This scheduler is configured to disallow Docker parameters.";

  @VisibleForTesting
  static final String EXECUTOR_REQUIRED_WITH_DOCKER =
      "This scheduler is configured to require an executor for Docker-based tasks.";

  @VisibleForTesting
  static final String CONTAINER_AND_IMAGE_ARE_MUTUALLY_EXCLUSIVE =
      "A task may not have both a Docker container and an image.";

  /**
   * Check validity of and populates defaults in a task configuration.  This will return a deep copy
   * of the provided task configuration with default configuration values applied, and configuration
   * map values sanitized and applied to their respective struct fields.
   *
   *
   * @param config Task config to validate and populate.
   * @return A reference to the modified {@code config} (for chaining).
   * @throws TaskDescriptionException If the task is invalid.
   */
  public ITaskConfig validateAndPopulate(ITaskConfig config) throws TaskDescriptionException {
    TaskConfig builder = config.newBuilder();

    if (!builder.isSetRequestedPorts()) {
      builder.setRequestedPorts(ImmutableSet.of());
    }

    maybeFillLinks(builder);

    if (config.isSetTier() && !UserProvidedStrings.isGoodIdentifier(config.getTier())) {
      throw new TaskDescriptionException("Tier contains illegal characters: " + config.getTier());
    }

    try {
      tierManager.getTier(config);
    } catch (IllegalArgumentException e) {
      throw new TaskDescriptionException(e.getMessage(), e);
    }

    if (!JobKeys.isValid(config.getJob())) {
      // Job key is set but invalid
      throw new TaskDescriptionException("Job key " + config.getJob() + " is invalid.");
    }

    // A task must either have an executor configuration or specify a Docker container.
    if (!builder.isSetExecutorConfig()
        && !(builder.isSetContainer() && builder.getContainer().isSetDocker())) {

      throw new TaskDescriptionException("Configuration may not be null");
    }

    // Maximize the usefulness of any thrown error message by checking required fields first.
    for (RequiredFieldValidator<?> validator : REQUIRED_FIELDS_VALIDATORS) {
      validator.validate(builder);
    }

    IConstraint constraint = getDedicatedConstraint(config);
    if (constraint != null) {
      if (!isValueConstraint(constraint.getConstraint())) {
        throw new TaskDescriptionException("A dedicated constraint must be of value type.");
      }

      IValueConstraint valueConstraint = constraint.getConstraint().getValue();

      if (valueConstraint.getValues().size() != 1) {
        throw new TaskDescriptionException("A dedicated constraint must have exactly one value");
      }

      String dedicatedRole = getRole(valueConstraint);
      if (!("*".equals(dedicatedRole) || config.getJob().getRole().equals(dedicatedRole))) {
        throw new TaskDescriptionException(
            "Only " + dedicatedRole + " may use hosts dedicated for that role.");
      }
    }

    Optional<Container._Fields> containerType;
    if (config.isSetContainer()) {
      IContainer containerConfig = config.getContainer();
      containerType = Optional.of(containerConfig.getSetField());
      if (containerConfig.isSetDocker()) {
        if (!containerConfig.getDocker().isSetImage()) {
          throw new TaskDescriptionException("A container must specify an image.");
        }
        if (containerConfig.getDocker().getParameters().isEmpty()) {
          for (Map.Entry<String, String> e : settings.defaultDockerParameters.entries()) {
            builder.getContainer().getDocker().addToParameters(
                new DockerParameter(e.getKey(), e.getValue()));
          }
        } else {
          if (!settings.allowDockerParameters) {
            throw new TaskDescriptionException(NO_DOCKER_PARAMETERS);
          }
        }

        if (settings.requireDockerUseExecutor && !config.isSetExecutorConfig()) {
          throw new TaskDescriptionException(EXECUTOR_REQUIRED_WITH_DOCKER);
        }
      }
    } else {
      // Default to mesos container type if unset.
      containerType = Optional.of(Container._Fields.MESOS);
    }

    if (!containerType.isPresent()) {
      throw new TaskDescriptionException("A job must have a container type.");
    }
    if (!settings.allowedContainerTypes.contains(containerType.get())) {
      throw new TaskDescriptionException(
          "This scheduler is not configured to allow the container type "
              + containerType.get().toString());
    }

    if (containerType.get() != Container._Fields.MESOS && config.isSetImage()) {
      throw new TaskDescriptionException(CONTAINER_AND_IMAGE_ARE_MUTUALLY_EXCLUSIVE);
    }

    return ITaskConfig.build(builder);
  }

  /**
   * Provides a filter for the given constraint name.
   *
   * @param name The name of the constraint.
   * @return A filter that matches the constraint.
   */
  public static Predicate<IConstraint> getConstraintByName(final String name) {
    return constraint -> constraint.getName().equals(name);
  }

  private static void maybeFillLinks(TaskConfig task) {
    if (task.getTaskLinksSize() == 0) {
      ImmutableMap.Builder<String, String> links = ImmutableMap.builder();
      if (task.getRequestedPorts().contains("health")) {
        links.put("health", "http://%host%:%port:health%");
      }
      if (task.getRequestedPorts().contains("http")) {
        links.put("http", "http://%host%:%port:http%");
      }
      task.setTaskLinks(links.build());
    }
  }

  /**
   * Thrown when an invalid task or job configuration is encountered.
   */
  public static class TaskDescriptionException extends Exception {
    public TaskDescriptionException(String msg, Exception e) {
      super(msg, e);
    }

    public TaskDescriptionException(String msg) {
      super(msg);
    }
  }
}
