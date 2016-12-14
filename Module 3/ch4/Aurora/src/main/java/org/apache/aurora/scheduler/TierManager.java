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
package org.apache.aurora.scheduler;

import java.util.Map;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonProperty;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Translates job tier configuration into a set of task traits/attributes.
 */
public interface TierManager {

  /**
   * Gets {@link TierInfo} instance representing task's tier details.
   *
   * @param taskConfig Task configuration to get tier for.
   * @return {@link TierInfo} for the given {@code taskConfig}.
   */
  TierInfo getTier(ITaskConfig taskConfig);

  class TierManagerImpl implements TierManager {
    private final TierConfig tierConfig;

    @VisibleForTesting
    public static class TierConfig {
      private final Map<String, TierInfo> tiers;

      @VisibleForTesting
      @JsonCreator
      public TierConfig(@JsonProperty("tiers") Map<String, TierInfo> tiers) {
        checkArgument(!tiers.isEmpty(), "Tiers cannot be empty.");
        this.tiers = ImmutableMap.copyOf(tiers);
      }

      @VisibleForTesting
      public Map<String, TierInfo> getTiers() {
        return tiers;
      }
    }

    @Inject
    TierManagerImpl(TierConfig tierConfig) {
      this.tierConfig = requireNonNull(tierConfig);
    }

    @Override
    public TierInfo getTier(ITaskConfig taskConfig) {
      checkArgument(
          !taskConfig.isSetTier() || tierConfig.tiers.containsKey(taskConfig.getTier()),
          format("Invalid tier '%s' in TaskConfig.", taskConfig.getTier()));

      return taskConfig.isSetTier()
          ? tierConfig.tiers.get(taskConfig.getTier())
          : tierConfig.getTiers().values().stream()
              // Backward compatibility mode until tier is required in TaskConfig (AURORA-1624).
              .filter(v -> v.isPreemptible() == !taskConfig.isProduction() && !v.isRevocable())
              .findFirst()
              .orElseThrow(() -> new IllegalStateException(
                  format("No matching implicit tier for task of job %s", taskConfig.getJob())));
    }
  }
}
