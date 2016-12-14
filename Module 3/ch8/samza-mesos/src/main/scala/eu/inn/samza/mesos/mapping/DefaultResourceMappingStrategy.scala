/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package eu.inn.samza.mesos.mapping

import scala.collection.mutable

class DefaultResourceMappingStrategy extends ResourceMappingStrategy {
  //called like this in TaskOfferMapper: strategy.mapResources(offers, tasks, constraints)
  def mapResources[R <% ResourceHolder, X](resourceHolders: Iterable[R], //offers
                                           objects: Set[X],              //tasks
                                           constraints: ResourceConstraints): Map[R, Set[X]] = {
    val unallocated = mutable.Set(objects.toSeq: _*)
    val holdersMap = mutable.Map[R, Set[X]]()

    for (holder <- resourceHolders) {
      holdersMap.put(holder, Set())

      val remaining = TempResourceHolder.fromHolder(holder)

      while (unallocated.size > 0 && satisfies(remaining, constraints)) {
        holdersMap(holder) += unallocated.head
        unallocated -= unallocated.head
        remaining.decreaseBy(constraints)
      }
    }

    holdersMap.toMap
  }
}