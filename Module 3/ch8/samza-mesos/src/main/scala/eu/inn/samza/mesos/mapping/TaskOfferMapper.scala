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

import eu.inn.samza.mesos.MesosTask
import org.apache.mesos.Protos.Offer

import scala.collection.JavaConversions._

object TaskOfferMapper {
  implicit def Offer2ResourceHolder(offer: Offer) = new ResourceHolder {

    override def getResourceList: Map[String, Double] =
      offer.getResourcesList.map(r => (r.getName, r.getScalar.getValue)).toMap

    override def getAttributeList: Map[String, String] =
      offer.getAttributesList.map(r => (r.getName, r.getText.getValue)).toMap
  }
}

class TaskOfferMapper(strategy: ResourceMappingStrategy) {

  import TaskOfferMapper.Offer2ResourceHolder

  private val constraints = new ResourceConstraints

  def addCpuConstraint(cores: Double): TaskOfferMapper = {
    constraints.requireResource("cpus", cores)
    this
  }

  def addMemConstraint(sizeMb: Double): TaskOfferMapper = {
    constraints.requireResource("mem", sizeMb)
    this
  }

  def addDiskConstraint(sizeMb: Double): TaskOfferMapper = {
    constraints.requireResource("disk", sizeMb)
    this
  }

  def addAttributeConstraint(attributes: (String, String)*): TaskOfferMapper = {
    for (attr <- attributes) constraints.requireSupport(attr._1, attr._2)
    this
  }

  def mapResources(offers: Iterable[Offer], tasks: Set[MesosTask]): Map[Offer, Set[MesosTask]] = {
    strategy.mapResources(offers, tasks, constraints)
  }
}