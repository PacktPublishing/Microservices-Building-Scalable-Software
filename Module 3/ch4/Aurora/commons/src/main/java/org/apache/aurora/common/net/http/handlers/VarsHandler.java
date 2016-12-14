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
package org.apache.aurora.common.net.http.handlers;

import java.util.Collections;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

import org.apache.aurora.common.stats.Stat;

/**
 * HTTP handler that prints all registered variables and their current values.
 *
 * @author William Farner
 */
@Path("/vars")
public class VarsHandler {

  private static final Function<Stat, String> VAR_PRINTER = stat -> stat.getName() + " " + stat.read();

  private final Supplier<Iterable<Stat<?>>> statSupplier;

  /**
   * Creates a new handler that will report stats from the provided supplier.
   *
   * @param statSupplier Stats supplier.
   */
  @Inject
  public VarsHandler(Supplier<Iterable<Stat<?>>> statSupplier) {
    this.statSupplier = Preconditions.checkNotNull(statSupplier);
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getVars() {
    List<String> lines = Lists.newArrayList(Iterables.transform(statSupplier.get(), VAR_PRINTER));
    Collections.sort(lines);
    return Joiner.on("\n").join(lines);
  }
}
