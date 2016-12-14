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

from __future__ import print_function

import logging
import posixpath
import subprocess
from multiprocessing.pool import ThreadPool

from pystachio import Environment, Required, String
from twitter.common import log

from apache.aurora.client.api import AuroraClientAPI
from apache.aurora.client.base import AURORA_V2_USER_AGENT_NAME, combine_messages
from apache.aurora.common.cluster import Cluster
from apache.aurora.config.schema.base import MesosContext
from apache.thermos.config.schema import ThermosContext

from gen.apache.aurora.api.constants import LIVE_STATES
from gen.apache.aurora.api.ttypes import JobKey, ResponseCode, TaskQuery


class CommandRunnerTrait(Cluster.Trait):
  slave_root          = Required(String)  # noqa
  slave_run_directory = Required(String)  # noqa


class DistributedCommandRunner(object):

  @classmethod
  def make_executor_path(cls, cluster, executor_name):
    parameters = cls.sandbox_args(cluster)
    parameters.update(executor_name=executor_name)
    return posixpath.join(
        '%(slave_root)s',
        'slaves/*/frameworks/*/executors/%(executor_name)s/runs',
        '%(slave_run_directory)s'
    ) % parameters

  @classmethod
  def thermos_sandbox(cls, cluster, executor_sandbox=False):
    sandbox = cls.make_executor_path(cluster, 'thermos-{{thermos.task_id}}')
    return sandbox if executor_sandbox else posixpath.join(sandbox, 'sandbox')

  @classmethod
  def sandbox_args(cls, cluster):
    cluster = cluster.with_trait(CommandRunnerTrait)
    return {'slave_root': cluster.slave_root, 'slave_run_directory': cluster.slave_run_directory}

  @classmethod
  def substitute(cls, command, task, cluster, **kw):
    prefix_command = 'cd %s;' % cls.thermos_sandbox(cluster, **kw)
    thermos_namespace = ThermosContext(
        task_id=task.assignedTask.taskId,
        ports=task.assignedTask.assignedPorts)
    mesos_namespace = MesosContext(instance=task.assignedTask.instanceId)
    command = String(prefix_command + command) % Environment(
        thermos=thermos_namespace,
        mesos=mesos_namespace)
    return command.get()

  @classmethod
  def query_from(cls, role, env, job):
    return TaskQuery(statuses=LIVE_STATES, jobKeys=[JobKey(role=role, environment=env, name=job)])

  def __init__(self, cluster, role, env, jobs, ssh_user=None, ssh_options=None, log_fn=log.log):
    self._cluster = cluster
    self._api = AuroraClientAPI(
        cluster=cluster,
        user_agent=AURORA_V2_USER_AGENT_NAME)
    self._role = role
    self._env = env
    self._jobs = jobs
    self._ssh_user = ssh_user if ssh_user else self._role
    self._ssh_options = ssh_options if ssh_options else []
    self._log = log_fn

  def execute(self, args):
    hostname, role, command = args
    ssh_command = ['ssh', '-n', '-q']
    ssh_command += self._ssh_options
    ssh_command += ['%s@%s' % (role, hostname), command]
    self._log(logging.DEBUG, "Running command: %s" % ssh_command)
    po = subprocess.Popen(ssh_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = po.communicate()
    return '\n'.join('%s:  %s' % (hostname, line) for line in output[0].splitlines())

  def resolve(self):
    for job in self._jobs:
      resp = self._api.query(self.query_from(self._role, self._env, job))
      if resp.responseCode != ResponseCode.OK:
        self._log(logging.ERROR, 'Failed to query job: %s' % job)
        continue
      for task in resp.result.scheduleStatusResult.tasks:
        yield task

  def process_arguments(self, command, **kw):
    for task in self.resolve():
      host = task.assignedTask.slaveHost
      yield (host, self._ssh_user, self.substitute(command, task, self._cluster, **kw))

  def run(self, command, parallelism=1, **kw):
    threadpool = ThreadPool(processes=parallelism)
    for result in threadpool.imap_unordered(self.execute, self.process_arguments(command, **kw)):
      print(result)


class InstanceDistributedCommandRunner(DistributedCommandRunner):
  """A distributed command runner that only runs on specified instances of a job."""

  @classmethod
  def query_from(cls, role, env, job, instances=None):
    return TaskQuery(
        statuses=LIVE_STATES,
        jobKeys=[JobKey(role=role, environment=env, name=job)],
        instanceIds=instances)

  def __init__(self,
               cluster,
               role,
               env,
               job,
               ssh_user=None,
               ssh_options=None,
               instances=None,
               log_fn=logging.log):
    super(InstanceDistributedCommandRunner, self).__init__(
        cluster,
        role,
        env,
        [job],
        ssh_user,
        ssh_options,
        log_fn)
    self._job = job
    self._ssh_user = ssh_user if ssh_user else self._role
    self.instances = instances

  def resolve(self):
    resp = self._api.query(self.query_from(self._role, self._env, self._job, self.instances))
    if resp.responseCode == ResponseCode.OK:
      for task in resp.result.scheduleStatusResult.tasks:
        yield task
    else:
      self._log(
          logging.ERROR,
          'Error: could not retrieve task information for run command: %s' % combine_messages(resp))
      raise ValueError('Could not retrieve task information: %s' % combine_messages(resp))
