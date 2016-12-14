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

import org.apache.samza.config.{JobConfig, Config}
import scala.collection.JavaConversions._

object MesosConfig {
  val PACKAGE_PATH = "mesos.package.path"
  val DOCKER_IMAGE = "mesos.docker.image"
  val DOCKER_ENTRYPOINT_ARGUMENTS = "mesos.docker.entrypoint.arguments"
  val MASTER_CONNECT = "mesos.master.connect"

  val EXECUTOR_MAX_MEMORY_MB = "mesos.executor.memory.mb"
  val EXECUTOR_MAX_CPU_CORES = "mesos.executor.cpu.cores"
  val EXECUTOR_MAX_DISK_MB = "mesos.executor.disk.mb"
  val EXECUTOR_ATTRIBUTES = "mesos.executor.attributes"
  val EXECUTOR_TASK_COUNT = "mesos.executor.count"

  val SCHEDULER_USER = "mesos.scheduler.user"
  val SCHEDULER_ROLE = "mesos.scheduler.role"
  val SCHEDULER_JMX_ENABLED = "mesos.scheduler.jmx.enabled"
  val SCHEDULER_FAILOVER_TIMEOUT = "mesos.scheduler.failover.timeout"

  implicit def Config2Mesos(config: Config) = new MesosConfig(config)
}

class MesosConfig(config: Config) extends JobConfig(config) {
  def getExecutorMaxMemoryMb: Double = getOption(MesosConfig.EXECUTOR_MAX_MEMORY_MB).map(_.toDouble).getOrElse(1024)

  def getExecutorMaxCpuCores: Double = getOption(MesosConfig.EXECUTOR_MAX_CPU_CORES).map(_.toDouble).getOrElse(1)

  def getExecutorMaxDiskMb: Double = getOption(MesosConfig.EXECUTOR_MAX_DISK_MB).map(_.toDouble).getOrElse(1024)

  def getExecutorAttributes: Map[String, String] = {
    subset(MesosConfig.EXECUTOR_ATTRIBUTES, true).entrySet().map(e => (e.getKey, e.getValue)).toMap
  }

  def getPackagePath = getOption(MesosConfig.PACKAGE_PATH)

  def getDockerImage = getOption(MesosConfig.DOCKER_IMAGE)

  def getDockerEntrypointArguments = getList(MesosConfig.DOCKER_ENTRYPOINT_ARGUMENTS, Nil)

  def getTaskCount: Option[Int] = getOption(MesosConfig.EXECUTOR_TASK_COUNT).map(_.toInt)

  def getJmxServerEnabled = getBoolean(MesosConfig.SCHEDULER_JMX_ENABLED, true)

  // https://issues.apache.org/jira/browse/MESOS-1219
  def getFailoverTimeout = getLong(MesosConfig.SCHEDULER_FAILOVER_TIMEOUT, Long.MaxValue)

  def getMasterConnect = getOption(MesosConfig.MASTER_CONNECT)

  def getUser = get(MesosConfig.SCHEDULER_USER, "")

  def getRole = getOption(MesosConfig.SCHEDULER_ROLE)
}
