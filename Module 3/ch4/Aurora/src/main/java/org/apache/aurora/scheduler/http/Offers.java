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

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import org.apache.aurora.scheduler.HostOffer;
import org.apache.aurora.scheduler.offers.OfferManager;
import org.apache.mesos.Protos.Attribute;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.Value.Range;

import static org.apache.mesos.Protos.Offer;

/**
 * Servlet that exposes resource offers that the scheduler is currently retaining.
 */
@Path("/offers")
public class Offers {

  private final OfferManager offerManager;

  @Inject
  Offers(OfferManager offerManager) {
    this.offerManager = Objects.requireNonNull(offerManager);
  }

  /**
   * Dumps the offers queued in the scheduler.
   *
   * @return HTTP response.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getOffers() {
    return Response.ok(
        FluentIterable.from(offerManager.getOffers()).transform(TO_BEAN).toList()).build();
  }

  private static final Function<ExecutorID, String> EXECUTOR_ID_TOSTRING = ExecutorID::getValue;

  private static final Function<Range, Object> RANGE_TO_BEAN =
      range -> range.getBegin() + "-" + range.getEnd();

  private static final Function<Attribute, Object> ATTRIBUTE_TO_BEAN =
      attr -> {
        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
        builder.put("name", attr.getName());
        if (attr.hasScalar()) {
          builder.put("scalar", attr.getScalar().getValue());
        }
        if (attr.hasRanges()) {
          builder.put("ranges", immutable(attr.getRanges().getRangeList(), RANGE_TO_BEAN));
        }
        if (attr.hasSet()) {
          builder.put("set", attr.getSet().getItemList());
        }
        if (attr.hasText()) {
          builder.put("text", attr.getText().getValue());
        }
        return builder.build();
      };

  private static final Function<Resource, Object> RESOURCE_TO_BEAN =
      resource -> {
        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
        builder.put("name", resource.getName());
        if (resource.hasScalar()) {
          builder.put("scalar", resource.getScalar().getValue());
        }
        if (resource.hasRanges()) {
          builder.put("ranges", immutable(resource.getRanges().getRangeList(), RANGE_TO_BEAN));
        }
        if (resource.hasSet()) {
          builder.put("set", resource.getSet().getItemList());
        }
        if (resource.hasRevocable()) {
          builder.put("revocable", "true");
        }
        return builder.build();
      };

  private static <A, B> Iterable<B> immutable(Iterable<A> iterable, Function<A, B> transform) {
    return FluentIterable.from(iterable).transform(transform).toList();
  }

  private static final Function<HostOffer, Map<String, ?>> TO_BEAN =
      hostOffer -> {
        Offer offer = hostOffer.getOffer();
        return ImmutableMap.<String, Object>builder()
            .put("id", offer.getId().getValue())
            .put("framework_id", offer.getFrameworkId().getValue())
            .put("slave_id", offer.getSlaveId().getValue())
            .put("hostname", offer.getHostname())
            .put("resources", immutable(offer.getResourcesList(), RESOURCE_TO_BEAN))
            .put("attributes", immutable(offer.getAttributesList(), ATTRIBUTE_TO_BEAN))
            .put("executor_ids", immutable(offer.getExecutorIdsList(), EXECUTOR_ID_TOSTRING))
            .build();
      };
}
