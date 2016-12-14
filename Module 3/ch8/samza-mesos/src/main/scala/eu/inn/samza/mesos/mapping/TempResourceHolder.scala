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

// todo: refactor
object TempResourceHolder {
  def fromHolder(holder: ResourceHolder) = new TempResourceHolder(holder.getResourceList, holder.getAttributeList)
}

class TempResourceHolder(resourceList: Map[String, Double], attributeList: Map[String, String]) extends ResourceHolder {
  private val remaining: mutable.Map[String, Double] = mutable.Map(resourceList.toSeq: _*)

  override def getResourceList: Map[String, Double] = remaining.toMap

  override def getAttributeList: Map[String, String] = attributeList

  def decreaseBy(constraints: ResourceConstraints): Unit = {
    for (r <- constraints.resources) remaining(r._1) -= r._2
  }
}

