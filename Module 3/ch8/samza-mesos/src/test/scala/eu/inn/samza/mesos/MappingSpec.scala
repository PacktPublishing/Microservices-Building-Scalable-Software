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

package eu.inn.samza.mesos

import eu.inn.samza.mesos.mapping.{TempResourceHolder, ResourceConstraints, DefaultResourceMappingStrategy}
import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers

class MappingSpec extends FunSpec with ShouldMatchers {

  describe("Default resource mapping strategy") {

    it("should allocate as much resources as possible") {
      val strategy = new DefaultResourceMappingStrategy()

      val constraints = new ResourceConstraints()
        .requireResource("cpus", 2.0)
        .requireResource("mem", 512.0)

      val tasks = (1 to 5).map(_ => new Object).toSet

      val offers = List(
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 5120.0), Map()),
        new TempResourceHolder(Map("cpus" -> 1.0, "mem" -> 1024.0), Map()),
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 512.0), Map()))

      val mapped = strategy.mapResources(offers, tasks, constraints)

      mapped.keySet should have size 3

      mapped(offers(0)) should have size 2
      mapped(offers(1)) should have size 0
      mapped(offers(2)) should have size 1
    }

    it("should leave unallocated resources as is") {
      val strategy = new DefaultResourceMappingStrategy()

      val constraints = new ResourceConstraints()
        .requireResource("cpus", 2.0)
        .requireResource("mem", 512.0)

      val tasks = (1 to 5).map(_ => new Object).toSet

      val offers = List(
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 5120.0), Map()),
        new TempResourceHolder(Map("cpus" -> 10.0, "mem" -> 5120.0), Map()),
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 512.0), Map()))

      val mapped = strategy.mapResources(offers, tasks, constraints)

      mapped.keySet should have size 3

      mapped(offers(0)) should have size 2
      mapped(offers(1)) should have size 3
      mapped(offers(2)) should have size 0
    }

    it("should match required attributes") {
      val strategy = new DefaultResourceMappingStrategy()

      val constraints = new ResourceConstraints()
        .requireResource("cpus", 2.0)
        .requireResource("mem", 512.0)
        .requireSupport("az", "^(CA|NY)$")
        .requireSupport("size", "BIG")

      val tasks = (1 to 5).map(_ => new Object).toSet

      val offers = List(
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 1024.0), Map()),
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 1024.0), Map("az" -> "NY", "size" -> "notBIG")),
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 1024.0), Map("az" -> "CA", "size" -> "BIG")),
        new TempResourceHolder(Map("cpus" -> 5.0, "mem" -> 1024.0), Map("az" -> "NY", "size" -> "BIG")))

      val mapped = strategy.mapResources(offers, tasks, constraints)

      mapped.keySet should have size 4

      mapped(offers(0)) should have size 0
      mapped(offers(1)) should have size 0
      mapped(offers(2)) should have size 2
      mapped(offers(3)) should have size 2
    }
  }
}
