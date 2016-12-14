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

import java.util.{Map => JMap}
import java.util.UUID

import org.apache.mesos.Protos._
import org.apache.samza.config.Config
import eu.inn.samza.mesos.MesosConfig.Config2Mesos
import org.apache.samza.config.TaskConfig.Config2Task
import org.apache.samza.container.TaskNamesToSystemStreamPartitions
import org.apache.samza.job.{CommandBuilder, ShellCommandBuilder}
import org.apache.samza.util.Logging

import scala.collection.JavaConversions._

class MesosTask(config: Config,
                state: SamzaSchedulerState,
                val samzaContainerId: Int) extends Logging {

  /** When the returned task's ID is accessed, it will be created with a new UUID. */
  def copyWithNewId: MesosTask = new MesosTask(config, state, samzaContainerId)

  lazy val mesosTaskId: String = s"${config.getName.get}-samza-container-${samzaContainerId}-${UUID.randomUUID.toString}"
  lazy val mesosTaskName: String = mesosTaskId

  lazy val samzaContainerName: String = s"${config.getName.get}-container-${samzaContainerId}"

  //TODO the code below here could use some refactoring, especially related to the config.getPackagePath vs config.getDockerImage logic...

  lazy val getSamzaCommandBuilder: CommandBuilder = {
    val sspTaskNames: TaskNamesToSystemStreamPartitions = state.samzaContainerIdToSSPTaskNames.getOrElse(samzaContainerId, TaskNamesToSystemStreamPartitions())
    val cmdBuilderClassName = config.getCommandClass.getOrElse(classOf[ShellCommandBuilder].getName)
    Class.forName(cmdBuilderClassName).newInstance.asInstanceOf[CommandBuilder]
      .setConfig(config)
      .setName(samzaContainerName)
      .setTaskNameToSystemStreamPartitionsMapping(sspTaskNames.getJavaFriendlyType)
      .setTaskNameToChangeLogPartitionMapping(
        state.samzaTaskNameToChangeLogPartitionMapping.map(kv => kv._1 -> Integer.valueOf(kv._2))
      )
  }

  def commandInfoUri(packagePath: String): CommandInfo.URI = CommandInfo.URI.newBuilder().setValue(packagePath).setExtract(true).build()

  val samzaContainerEnvVarPrefix = "SAMZA_CONTAINER_ENV_"

  def getBuiltMesosEnvironment(envMap: JMap[String, String]): Environment = {
    val memory = config.getExecutorMaxMemoryMb
    val javaHeapOpts = ("JAVA_HEAP_OPTS" -> s"-Xms${memory}M -Xmx${memory}M")
    val samzaContainerEnvVars = scala.sys.env.filter(_._1 startsWith samzaContainerEnvVarPrefix).map { case (key, value) => (key.replaceAll(samzaContainerEnvVarPrefix, ""), value) }
    val mesosEnvironmentBuilder: Environment.Builder = Environment.newBuilder()
    (envMap + javaHeapOpts ++ samzaContainerEnvVars) foreach { case (name, value) =>
      mesosEnvironmentBuilder.addVariables(
        Environment.Variable.newBuilder()
          .setName(name)
          .setValue(value)
          .build()
      )
    }
    mesosEnvironmentBuilder.build()
  }

  lazy val getBuiltMesosTaskID: TaskID = TaskID.newBuilder().setValue(mesosTaskId).build()

  lazy val getBuiltMesosCommandInfo: CommandInfo = {
    val samzaCommandBuilder = getSamzaCommandBuilder
    val builder = CommandInfo.newBuilder()
      .setEnvironment(getBuiltMesosEnvironment(samzaCommandBuilder.buildEnvironment()))
    info(s"Docker entrypoint arguments: ${config.getDockerEntrypointArguments}")
    if (config.getDockerEntrypointArguments.nonEmpty) {
      builder.setShell(false).addAllArguments(config.getDockerEntrypointArguments)
      debug(s"Using Docker ENTRYPOINT arguments: ${config.getDockerEntrypointArguments.mkString(",")}")
    } else {
      val pathPrefix = if (config.getDockerImage.nonEmpty) "/samza/" else ""
      val command = pathPrefix + samzaCommandBuilder.buildCommand()
      debug(s"Using command: $command")
      builder.setShell(true).setValue(command)
    }
    config.getPackagePath.foreach(p => builder.addUris(commandInfoUri(p)))
    builder.build()
  }

  def containerInfo(image: String): ContainerInfo = {
    debug(s"Using Docker image $image")
    ContainerInfo.newBuilder()
      .setType(ContainerInfo.Type.DOCKER)
      .setDocker(ContainerInfo.DockerInfo.newBuilder().setImage(image).build())
      .build()
  }

  def setCommandAndMaybeContainer(builder: TaskInfo.Builder): TaskInfo.Builder = {
    if (config.getPackagePath.isEmpty && config.getDockerImage.isEmpty)
      throw new IllegalArgumentException(s"Either ${MesosConfig.PACKAGE_PATH} or ${MesosConfig.DOCKER_IMAGE} must be specified")
    builder.setCommand(getBuiltMesosCommandInfo)
    config.getDockerImage.foreach(image => builder.setContainer(containerInfo(image)))
    builder
  }

  def scalarResource(name: String, value: Double): Resource =
    Resource.newBuilder.setName(name).setType(Value.Type.SCALAR).setScalar(Value.Scalar.newBuilder().setValue(value)).build()

  def getBuiltMesosTaskInfo(slaveId: SlaveID): TaskInfo = {
    val builder = TaskInfo.newBuilder()
      .setTaskId(getBuiltMesosTaskID)
      .setName(mesosTaskName)
      .setSlaveId(slaveId)
      .addResources(scalarResource("cpus", config.getExecutorMaxCpuCores))
      .addResources(scalarResource("mem", config.getExecutorMaxMemoryMb))
      .addResources(scalarResource("disk", config.getExecutorMaxDiskMb))
    setCommandAndMaybeContainer(builder).build()
  }
}
