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
package org.apache.aurora.scheduler.filter;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.inject.Inject;

import org.apache.aurora.common.inject.TimedInterceptor.Timed;
import org.apache.aurora.common.quantity.Data;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.gen.TaskConstraint;
import org.apache.aurora.scheduler.configuration.ConfigurationManager;
import org.apache.aurora.scheduler.configuration.executor.ExecutorSettings;
import org.apache.aurora.scheduler.resources.ResourceSlot;
import org.apache.aurora.scheduler.resources.ResourceType;
import org.apache.aurora.scheduler.storage.entities.IAttribute;
import org.apache.aurora.scheduler.storage.entities.IConstraint;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.gen.MaintenanceMode.DRAINED;
import static org.apache.aurora.gen.MaintenanceMode.DRAINING;
import static org.apache.aurora.scheduler.configuration.ConfigurationManager.DEDICATED_ATTRIBUTE;
import static org.apache.aurora.scheduler.resources.ResourceType.CPUS;
import static org.apache.aurora.scheduler.resources.ResourceType.DISK_MB;
import static org.apache.aurora.scheduler.resources.ResourceType.PORTS;
import static org.apache.aurora.scheduler.resources.ResourceType.RAM_MB;

/**
 * Implementation of the scheduling filter that ensures resource requirements of tasks are
 * fulfilled, and that tasks are allowed to run on the given machine.
 */
public class SchedulingFilterImpl implements SchedulingFilter {
  private static final Set<MaintenanceMode> VETO_MODES = EnumSet.of(DRAINING, DRAINED);

  @VisibleForTesting
  static int scale(double value, int range) {
    return Math.min(
        VetoType.INSUFFICIENT_RESOURCES.getScore(),
        (int) (VetoType.INSUFFICIENT_RESOURCES.getScore() * value) / range);
  }

  private static void maybeAddVeto(
      ImmutableSet.Builder<Veto> vetoes,
      ResourceType resourceType,
      double available,
      double requested) {

    double tooLarge = requested - available;
    if (tooLarge > 0) {
      vetoes.add(Veto.insufficientResources(
          resourceType.getAuroraName(),
          scale(tooLarge, resourceType.getScalingRange())));
    }
  }

  private static Set<Veto> getResourceVetoes(ResourceSlot available, ResourceSlot required) {
    ImmutableSet.Builder<Veto> vetoes = ImmutableSet.builder();
    maybeAddVeto(vetoes, CPUS, available.getNumCpus(), required.getNumCpus());
    maybeAddVeto(vetoes, RAM_MB, available.getRam().as(Data.MB), required.getRam().as(Data.MB));
    maybeAddVeto(vetoes, DISK_MB, available.getDisk().as(Data.MB), required.getDisk().as(Data.MB));
    maybeAddVeto(vetoes, PORTS, available.getNumPorts(), required.getNumPorts());
    return vetoes.build();
  }

  private static boolean isValueConstraint(IConstraint constraint) {
    return constraint.getConstraint().getSetField() == TaskConstraint._Fields.VALUE;
  }

  private static final Ordering<IConstraint> VALUES_FIRST = Ordering.from(
      new Comparator<IConstraint>() {
        @Override
        public int compare(IConstraint a, IConstraint b) {
          if (a.getConstraint().getSetField() == b.getConstraint().getSetField()) {
            return 0;
          }
          return isValueConstraint(a) ? -1 : 1;
        }
      });

  private final ExecutorSettings executorSettings;

  @Inject
  @VisibleForTesting
  public SchedulingFilterImpl(ExecutorSettings executorSettings) {
    this.executorSettings = requireNonNull(executorSettings);
  }

  private Optional<Veto> getConstraintVeto(
      Iterable<IConstraint> taskConstraints,
      AttributeAggregate jobState,
      Iterable<IAttribute> offerAttributes) {

    for (IConstraint constraint : VALUES_FIRST.sortedCopy(taskConstraints)) {
      Optional<Veto> veto = ConstraintMatcher.getVeto(jobState, offerAttributes, constraint);
      if (veto.isPresent()) {
        // Break early to avoid potentially-expensive operations to satisfy other constraints.
        return veto;
      }
    }

    return Optional.absent();
  }

  private Optional<Veto> getMaintenanceVeto(MaintenanceMode mode) {
    return VETO_MODES.contains(mode)
        ? Optional.of(Veto.maintenance(mode.toString().toLowerCase()))
        : Optional.absent();
  }

  private boolean isDedicated(IHostAttributes attributes) {
    return Iterables.any(
        attributes.getAttributes(),
        new ConstraintMatcher.NameFilter(DEDICATED_ATTRIBUTE));
  }

  @Timed("scheduling_filter")
  @Override
  public Set<Veto> filter(UnusedResource resource, ResourceRequest request) {
    // Apply veto filtering rules from higher to lower score making sure we cut over and return
    // early any time a veto from a score group is applied. This helps to more accurately report
    // a veto reason in the NearestFit.

    // 1. Dedicated constraint check (highest score).
    if (!ConfigurationManager.isDedicated(request.getConstraints())
        && isDedicated(resource.getAttributes())) {

      return ImmutableSet.of(Veto.dedicatedHostConstraintMismatch());
    }

    // 2. Host maintenance check.
    Optional<Veto> maintenanceVeto = getMaintenanceVeto(resource.getAttributes().getMode());
    if (maintenanceVeto.isPresent()) {
      return maintenanceVeto.asSet();
    }

    // 3. Value and limit constraint check.
    Optional<Veto> constraintVeto = getConstraintVeto(
        request.getConstraints(),
        request.getJobState(),
        resource.getAttributes().getAttributes());

    if (constraintVeto.isPresent()) {
      return constraintVeto.asSet();
    }

    // 4. Resource check (lowest score).
    return getResourceVetoes(
        resource.getResourceSlot(),
        ResourceSlot.from(request.getTask()).add(executorSettings.getExecutorOverhead()));
  }
}
