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
package org.apache.aurora.scheduler.http;

import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.StoreProvider;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;

import static org.apache.aurora.gen.MaintenanceMode.DRAINED;
import static org.apache.aurora.gen.MaintenanceMode.DRAINING;
import static org.apache.aurora.gen.MaintenanceMode.SCHEDULED;

/**
 * Servlet that exposes the maintenance state of hosts.
 */
@Path("/maintenance")
public class Maintenance {
  private final Storage storage;

  @Inject
  Maintenance(Storage storage) {
    this.storage = Objects.requireNonNull(storage);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getHosts() {
    return storage.read(storeProvider -> {
      Multimap<MaintenanceMode, String> hostsByMode =
          Multimaps.transformValues(
            Multimaps.index(
                storeProvider.getAttributeStore().getHostAttributes(),
                IHostAttributes::getMode),
              IHostAttributes::getHost);

      Map<MaintenanceMode, Object> hosts = ImmutableMap.of(
          DRAINED, ImmutableSet.copyOf(hostsByMode.get(DRAINED)),
          SCHEDULED, ImmutableSet.copyOf(hostsByMode.get(SCHEDULED)),
          DRAINING, getTasksByHosts(storeProvider, hostsByMode.get(DRAINING)).asMap());
      return Response.ok(hosts).build();
    });
  }

  private Multimap<String, String> getTasksByHosts(StoreProvider provider, Iterable<String> hosts) {
    if (Iterables.isEmpty(hosts)) {
      return ImmutableMultimap.of();
    }

    ImmutableSet.Builder<IScheduledTask> drainingTasks = ImmutableSet.builder();
    drainingTasks.addAll(provider.getTaskStore()
        .fetchTasks(Query.slaveScoped(hosts).byStatus(Tasks.SLAVE_ASSIGNED_STATES)));
    return Multimaps.transformValues(
        Multimaps.index(drainingTasks.build(), Tasks::scheduledToSlaveHost),
        Tasks::id);
  }
}
