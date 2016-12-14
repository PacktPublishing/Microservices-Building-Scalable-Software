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

import eu.inn.samza.mesos.MesosConfig.Config2Mesos
import org.apache.mesos.Protos.{OfferID, Offer, TaskInfo}
import org.apache.samza.config.Config
import org.apache.samza.container.{TaskName, TaskNamesToSystemStreamPartitions}
import org.apache.samza.job.ApplicationStatus
import org.apache.samza.job.ApplicationStatus._
import org.apache.samza.util.{Logging, Util}

import scala.collection.mutable
import scala.collection.JavaConversions._

/*
Let's be very clear about terminology:
  - Samza task
    - the instance of StreamTask that processes messages from a single stream partition
    - Samza determines how many Samza tasks there are for the Samza job, and the assignment of Samza tasks to Samza containers
  - Samza container
    - the JVM process that contains 1 or more Samza tasks
    - the number of Samza containers for the Samza job is configurable (mesos.executor.count), defaults to 1
  - Samza job: 
    - the high-level processing, actually carried out by individual Samza containers & Samza tasks
    - a Samza job runs in Mesos as a Mesos framework, which schedules Mesos tasks (Samza containers) to run out in the Mesos cluster
  - Mesos task
    - 1 Samza container running on a Mesos slave machine
    - Mesos task = Samza container
    - So 1 Samza job = 1 or more Mesos tasks
  - Mesos framework
    - a Mesos scheduler that schedules Mesos tasks to run out in the Mesos cluster
    - a Mesos executor that actually runs each Mesos task
    - Mesos framework = Samza job

To summarize:
  - Samza job = Mesos framework
  - Samza container = Mesos task
  - Samza task has no Mesos equivalent

Note that both Mesos and Samza have a thing called "task", so need to qualify that term to avoid confusion
*/

class SamzaSchedulerState(config: Config) extends Logging {
  var currentStatus: ApplicationStatus = New //TODO should this get updated to other values at some point? possible values are: New, Running, SuccessfulFinish, UnsuccessfulFinish

  val samzaContainerCount: Int = config.getTaskCount.getOrElse({
    info(s"No ${MesosConfig.EXECUTOR_TASK_COUNT} specified. Defaulting to one Samza container (i.e. one Mesos task).")
    1
  })
  debug(s"Samza container (i.e. Mesos task) count: ${samzaContainerCount}")

  val samzaContainerIds = (0 until samzaContainerCount).toSet
  debug(s"Samza container IDs: ${samzaContainerIds}")

  val samzaContainerIdToSSPTaskNames: Map[Int, TaskNamesToSystemStreamPartitions] =
    Util.assignContainerToSSPTaskNames(config, samzaContainerCount)
  debug(s"Samza container ID to SSP task names: ${samzaContainerIdToSSPTaskNames}")

  val samzaTaskNameToChangeLogPartitionMapping: Map[TaskName, Int] =
    Util.getTaskNameToChangeLogPartitionMapping(config, samzaContainerIdToSSPTaskNames)
  debug(s"Samza task name to changelog partition mapping: ${samzaTaskNameToChangeLogPartitionMapping}")

  def newMesosTaskMapping(containerId: Int): (String, MesosTask) = {
    val task = new MesosTask(config, this, containerId)
    (task.mesosTaskId, task)
  }
  private[this] val mesosTasks: mutable.Map[String, MesosTask] = mutable.Map(samzaContainerIds.toSeq.map(newMesosTaskMapping): _*)
  debug(s"Mesos task IDs: ${mesosTasks.keys}")

  private[this] val unclaimedTaskIdSet: mutable.Set[String] = mutable.Set(mesosTasks.keys.toSeq: _*)
  private[this] val pendingTaskIdSet: mutable.Set[String] = mutable.Set()
  private[this] val runningTaskIdSet: mutable.Set[String] = mutable.Set()

  def unclaimedTaskIds: Set[String] = unclaimedTaskIdSet.toSet
  def pendingTaskIds: Set[String] = pendingTaskIdSet.toSet
  def runningTaskIds: Set[String] = runningTaskIdSet.toSet

  def unclaimedTasks: Set[MesosTask] = mesosTasks.filterKeys(unclaimedTaskIds).values.toSet
  def hasUnclaimedTasks: Boolean = unclaimedTaskIdSet.nonEmpty

  /** Unclaimed --> Pending */
  def tasksAreNowPending(taskIds: Set[String]): Unit = {
    unclaimedTaskIdSet --= taskIds
    pendingTaskIdSet ++= taskIds
  }

  /** Pending --> Running */
  def taskIsNowRunning(taskId: String): Unit = {
    pendingTaskIdSet -= taskId
    runningTaskIdSet += taskId
  }

  /** Running --> Unclaimed */
  def taskFailed(taskId: String): Unit = {
    //replace failed task with a copy that uses a new unique taskId
    //we observed a problem when re-running a task with same ID where it would always die 1st time and have to be re-ran again
    //also for the record, Marathon re-runs failed tasks with new unique IDs
    for (task <- mesosTasks.get(taskId)) {
      pendingTaskIdSet -= taskId
      runningTaskIdSet -= taskId
      unclaimedTaskIdSet -= taskId
      mesosTasks -= taskId

      val newTask = task.copyWithNewId
      val newTaskId = newTask.mesosTaskId
      mesosTasks += (newTaskId -> newTask)
      unclaimedTaskIdSet += newTaskId
      info(s"Mesos task ${taskId} failed, was replaced with ${newTaskId} and will be re-scheduled")
    }
  }

  def dump() = {
    info(s"Tasks state: unclaimed: ${unclaimedTaskIdSet.size}, pending: ${pendingTaskIdSet.size}, running: ${runningTaskIdSet.size}")
  }
}
