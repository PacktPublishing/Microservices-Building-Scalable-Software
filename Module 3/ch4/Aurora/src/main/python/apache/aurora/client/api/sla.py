#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import math
import time
from collections import defaultdict, namedtuple

from twitter.common import log

from apache.aurora.client.base import DEFAULT_GROUPING, format_response, group_hosts
from apache.aurora.common.aurora_job_key import AuroraJobKey

from gen.apache.aurora.api.constants import LIVE_STATES
from gen.apache.aurora.api.ttypes import ResponseCode, ScheduleStatus, TaskQuery


def job_key_from_scheduled(task, cluster):
  """Creates AuroraJobKey from the ScheduledTask.

  Arguments:
  task -- ScheduledTask to get job key from.
  cluster -- Cluster the task belongs to.
  """
  config = task.assignedTask.task
  return AuroraJobKey(
      cluster=cluster.name,
      role=config.job.role,
      env=config.job.environment,
      name=config.job.name
  )


def task_query(hosts=None, job_keys=None):
  """Creates TaskQuery optionally scoped by a job(s) or hosts.

  Arguments:
  hosts -- list of hostnames to scope the query by.
  job_keys -- list of AuroraJobKeys to scope the query by.
  """
  return TaskQuery(
      slaveHosts=set(hosts) if hosts else None,
      jobKeys=[k.to_thrift() for k in job_keys] if job_keys else None,
      statuses=LIVE_STATES)


class JobUpTimeSlaVector(object):
  """A grouping of job active tasks by:
      - instance: Map of instance ID -> instance uptime in seconds.
     Exposes an API for converting raw instance uptime data into job SLA metrics.
  """

  def __init__(self, tasks, now=None):
    self._tasks = tasks
    self._now = now or time.time()
    self._uptime_map = self._instance_uptime()

  def total_tasks(self):
    """Returns the total count of active tasks."""
    return len(self._uptime_map)

  def get_wait_time_to_sla(self, percentile, duration, total_tasks=None):
    """Returns an approximate wait time until the job reaches the specified SLA
       defined by percentile and duration.

    Arguments:
    percentile -- up count percentile to calculate wait time against.
    duration -- uptime duration to calculate wait time against.
    total_tasks -- optional total task count to calculate against.
    """
    upcount = self.get_task_up_count(duration, total_tasks)
    if upcount >= percentile:
      return 0

    # To get wait time to SLA:
    # - Calculate the desired number of up instances in order to satisfy the percentile.
    # - Find the desired index (x) in the instance list sorted in non-decreasing order of uptimes.
    #   If desired index outside of current element count -> return None for "infeasible".
    # - Calculate wait time as: duration - duration(x)
    elements = len(self._uptime_map)
    total = total_tasks or elements
    target_count = math.ceil(total * percentile / 100.0)
    index = elements - int(target_count)

    if index < 0 or index >= elements:
      return None
    else:
      return duration - sorted(self._uptime_map.values())[index]

  def get_task_up_count(self, duration, total_tasks=None):
    """Returns the percentage of job tasks that stayed up longer than duration.

    Arguments:
    duration -- uptime duration in seconds.
    total_tasks -- optional total task count to calculate against.
    """
    total = total_tasks or len(self._uptime_map)
    above = len([uptime for uptime in self._uptime_map.values() if uptime >= duration])
    return 100.0 * above / total if total else 0

  def get_job_uptime(self, percentile):
    """Returns the uptime (in seconds) of the job at the specified percentile.

    Arguments:
    percentile -- percentile to report uptime for.
    """
    if percentile <= 0 or percentile >= 100:
      raise ValueError('Percentile must be within (0, 100), got %r instead.' % percentile)

    total = len(self._uptime_map)
    value = math.floor(percentile / 100.0 * total)
    index = total - int(value) - 1
    return sorted(self._uptime_map.values())[index] if 0 <= index < total else 0

  def _instance_uptime(self):
    instance_map = {}
    for task in self._tasks:
      for event in task.taskEvents:
        if event.status == ScheduleStatus.RUNNING:
          instance_map[task.assignedTask.instanceId] = math.floor(
              self._now - event.timestamp / 1000)
          break
    return instance_map


JobUpTimeLimit = namedtuple('JobUpTimeLimit', ['job', 'percentage', 'duration_secs'])


JobUpTimeDetails = namedtuple('JobUpTimeDetails',
    ['job', 'predicted_percentage', 'safe', 'safe_in_secs'])


class DomainUpTimeSlaVector(object):
  """A grouping of all active tasks in the cluster by:
      - job: Map of job_key -> task. Provides logical mapping between jobs and their active tasks.
      - host: Map of hostname -> job_key. Provides logical mapping between hosts and their jobs.
     Exposes an API for querying safe domain details.
  """
  DEFAULT_MIN_INSTANCE_COUNT = 2

  def __init__(self, cluster, tasks, min_instance_count=DEFAULT_MIN_INSTANCE_COUNT, hosts=None):
    self._cluster = cluster
    self._tasks = tasks
    self._now = time.time()
    self._tasks_by_job, self._jobs_by_host, self._hosts_by_job = self._init_mappings(
        min_instance_count)
    self._host_filter = hosts

  def get_safe_hosts(self,
      percentage,
      duration,
      job_limits=None,
      grouping_function=DEFAULT_GROUPING):
    """Returns hosts safe to restart with respect to their job SLA.
       Every host is analyzed separately without considering other job hosts.

       Arguments:
       percentage -- default task up count percentage. Used if job_limits mapping is not found.
       duration -- default task uptime duration in seconds. Used if job_limits mapping is not found.
       job_limits -- optional SLA override map. Key: job key. Value JobUpTimeLimit. If specified,
                     replaces default percentage/duration within the job context.
       grouping_function -- grouping function to use to group hosts.
    """
    safe_groups = []
    for hosts, job_keys in self._iter_groups(
        self._jobs_by_host.keys(), grouping_function, self._host_filter):

      safe_hosts = defaultdict(list)
      for job_key in job_keys:
        job_hosts = hosts.intersection(self._hosts_by_job[job_key])
        job_duration = duration
        job_percentage = percentage
        if job_limits and job_key in job_limits:
          job_duration = job_limits[job_key].duration_secs
          job_percentage = job_limits[job_key].percentage

        filtered_percentage, _, _ = self._simulate_hosts_down(job_key, job_hosts, job_duration)
        if filtered_percentage < job_percentage:
          break

        for host in job_hosts:
          safe_hosts[host].append(JobUpTimeLimit(job_key, filtered_percentage, job_duration))

      else:
        safe_groups.append(safe_hosts)

    return safe_groups

  def probe_hosts(self, percentage, duration, grouping_function=DEFAULT_GROUPING):
    """Returns predicted job SLAs following the removal of provided hosts.

       For every given host creates a list of JobUpTimeDetails with predicted job SLA details
       in case the host is restarted, including: host, job_key, predicted up count, whether
       the predicted job SLA >= percentage and the expected wait time in seconds for the job
       to reach its SLA.

       Arguments:
       percentage -- task up count percentage.
       duration -- task uptime duration in seconds.
       grouping_function -- grouping function to use to group hosts.
    """
    probed_groups = []
    for hosts, job_keys in self._iter_groups(self._host_filter or [], grouping_function):
      probed_hosts = defaultdict(list)
      for job_key in job_keys:
        job_hosts = hosts.intersection(self._hosts_by_job[job_key])
        filtered_percentage, total_count, filtered_vector = self._simulate_hosts_down(
            job_key, job_hosts, duration)

        # Calculate wait time to SLA in case down host violates job's SLA.
        if filtered_percentage < percentage:
          safe = False
          wait_to_sla = filtered_vector.get_wait_time_to_sla(percentage, duration, total_count)
        else:
          safe = True
          wait_to_sla = 0

        for host in job_hosts:
          probed_hosts[host].append(
              JobUpTimeDetails(job_key, filtered_percentage, safe, wait_to_sla))

      if probed_hosts:
        probed_groups.append(probed_hosts)

    return probed_groups

  def _iter_groups(self, hosts_to_group, grouping_function, host_filter=None):
    groups = group_hosts(hosts_to_group, grouping_function)
    for _, hosts in sorted(groups.items(), key=lambda v: v[0]):
      job_keys = set()
      for host in hosts:
        if host_filter and host not in self._host_filter:
          continue
        job_keys = job_keys.union(self._jobs_by_host.get(host, set()))
      yield hosts, job_keys

  def _create_group_results(self, group, uptime_details):
    result = defaultdict(list)
    for host in group.keys():
      result[host].append(uptime_details)

  def _simulate_hosts_down(self, job_key, hosts, duration):
    unfiltered_tasks = self._tasks_by_job[job_key]

    # Get total job task count to use in SLA calculation.
    total_count = len(unfiltered_tasks)

    # Get a list of job tasks that would remain after the affected hosts go down
    # and create an SLA vector with these tasks.
    filtered_tasks = [task for task in unfiltered_tasks
                      if task.assignedTask.slaveHost not in hosts]
    filtered_vector = JobUpTimeSlaVector(filtered_tasks, self._now)

    # Calculate the SLA that would be in effect should the host go down.
    filtered_percentage = filtered_vector.get_task_up_count(duration, total_count)

    return filtered_percentage, total_count, filtered_vector

  def _init_mappings(self, count):
    tasks_by_job = defaultdict(list)
    for task in self._tasks:
      if task.assignedTask.task.production:
        tasks_by_job[job_key_from_scheduled(task, self._cluster)].append(task)

    # Filter jobs by the min instance count.
    tasks_by_job = defaultdict(list, ((job, tasks) for job, tasks
        in tasks_by_job.items() if len(tasks) >= count))

    jobs_by_host = defaultdict(set)
    hosts_by_job = defaultdict(set)
    for job_key, tasks in tasks_by_job.items():
      for task in tasks:
        host = task.assignedTask.slaveHost
        jobs_by_host[host].add(job_key)
        hosts_by_job[job_key].add(host)

    return tasks_by_job, jobs_by_host, hosts_by_job


class Sla(object):
  """Defines methods for generating job uptime metrics required for monitoring job SLA."""

  def __init__(self, scheduler):
    self._scheduler = scheduler

  def get_job_uptime_vector(self, job_key):
    """Returns a JobUpTimeSlaVector object for the given job key.

    Arguments:
    job_key -- job to create a task uptime vector for.
    """
    return JobUpTimeSlaVector(self._get_tasks(task_query(job_keys=[job_key])))

  def get_domain_uptime_vector(self, cluster, min_instance_count, hosts=None):
    """Returns a DomainUpTimeSlaVector object with all available job uptimes.

    Arguments:
    cluster -- Cluster to get vector for.
    min_instance_count -- Minimum job instance count to consider for domain uptime calculations.
    hosts -- optional list of hostnames to query by.
    """
    tasks = self._get_tasks(task_query(hosts=hosts)) if hosts else None
    job_keys = set(job_key_from_scheduled(t, cluster) for t in tasks) if tasks else None

    # Avoid full cluster pull if job_keys are missing for any reason but the hosts are specified.
    job_tasks = [] if hosts and not job_keys else self._get_tasks(task_query(job_keys=job_keys))
    return DomainUpTimeSlaVector(
        cluster,
        job_tasks,
        min_instance_count=min_instance_count,
        hosts=hosts)

  def _get_tasks(self, task_query):
    resp = self._scheduler.getTasksWithoutConfigs(task_query)
    log.info(format_response(resp))
    if resp.responseCode != ResponseCode.OK:
      return []
    return resp.result.scheduleStatusResult.tasks
