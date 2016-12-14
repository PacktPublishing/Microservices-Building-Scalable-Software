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

import textwrap
import unittest

from mock import Mock, create_autospec, patch

from apache.aurora.client.cli.context import AuroraCommandContext
from apache.aurora.client.hooks.hooked_api import HookedAuroraClientAPI
from apache.aurora.common.aurora_job_key import AuroraJobKey
from apache.aurora.common.cluster import Cluster
from apache.aurora.common.clusters import CLUSTERS, Clusters

from ...api_util import SchedulerProxyApiSpec, SchedulerThriftApiSpec

from gen.apache.aurora.api.constants import ACTIVE_STATES
from gen.apache.aurora.api.ttypes import (
    AssignedTask,
    ExecutorConfig,
    JobKey,
    Response,
    ResponseCode,
    ResponseDetail,
    Result,
    ScheduledTask,
    ScheduleStatus,
    ScheduleStatusResult,
    TaskConfig,
    TaskEvent,
    TaskQuery
)


def mock_verb_options(verb):
  # Handle default values opt.kwargs.get('default')
  def opt_name(opt):
    return opt.name.lstrip('--').replace('-', '_')

  def name_or_dest(opt):
    """Prefers 'dest' if available otherwise defaults to name."""
    return opt.kwargs.get('dest') if 'dest' in opt.kwargs else opt_name(opt)

  options = Mock(
    spec_set=[name_or_dest(opt) for opt in verb.get_options()]
  )

  # Apply default values to options.
  for opt in verb.get_options():
    if 'default' in opt.kwargs:
      setattr(
          options,
          name_or_dest(opt),
          opt.kwargs.get('default'))
  return options


class FakeAuroraCommandContext(AuroraCommandContext):
  def __init__(self):
    super(FakeAuroraCommandContext, self).__init__()
    self.status = []
    self.fake_api = self.create_mock_api()
    self.task_result = []
    self.out = []
    self.err = []
    self.config = None

  def get_api(self, cluster):
    return self.fake_api

  @classmethod
  def create_mock_api(cls):
    """Builds up a mock API object, with a mock SchedulerProxy.
    Returns the API and the proxy"""
    mock_scheduler_proxy = create_autospec(spec=SchedulerProxyApiSpec, instance=True)
    mock_scheduler_proxy.url = "http://something_or_other"
    mock_scheduler_proxy.scheduler_client.return_value = mock_scheduler_proxy
    mock_api = create_autospec(spec=HookedAuroraClientAPI)
    mock_api.scheduler_proxy = mock_scheduler_proxy
    return mock_api

  def print_out(self, msg, indent=0):
    indent_str = " " * indent
    self.out.append("%s%s" % (indent_str, msg))

  def print_err(self, msg, indent=0):
    indent_str = " " * indent
    self.err.append("%s%s" % (indent_str, msg))

  def get_job_config(self, jobkey, config_file):
    if not self.config:
      return super(FakeAuroraCommandContext, self).get_job_config(jobkey, config_file)
    else:
      return self.config

  def get_out(self):
    return self.out

  def get_out_str(self):
    return '\n'.join(self.out)

  def get_err(self):
    return self.err

  def add_expected_status_query_result(self, expected_result):
    self.add_task_result(expected_result)
    self.fake_api.check_status.side_effect = self.task_result

  def add_expected_query_result(self, expected_result, job_key=None):
    self.add_task_result(expected_result)
    self.fake_api.query_no_configs.side_effect = self.task_result
    if job_key:
      self.fake_api.build_query.return_value = TaskQuery(
          jobKeys=[job_key.to_thrift()], statuses=ACTIVE_STATES)

  def add_task_result(self, expected_result):
    self.task_result.append(expected_result)
    # each call adds an expected query result, in order.
    self.fake_api.scheduler_proxy.getTasksWithoutConfigs.side_effect = self.task_result

  def add_config(self, config):
    self.config = config


class AuroraClientCommandTest(unittest.TestCase):
  FAKE_TIME = 42131

  def setUp(self):
    patcher = patch('webbrowser.open_new_tab')
    self.mock_webbrowser = patcher.start()
    self.addCleanup(patcher.stop)

  def run(self, result=None):
    # Since CLUSTERS is a global value that evaluates code on import this is the best way to
    # ensure it does not pollute any tests.
    with CLUSTERS.patch(self.TEST_CLUSTERS._clusters.values()):
      super(AuroraClientCommandTest, self).run(result)

  @classmethod
  def create_blank_response(cls, code, msg):
    return Response(responseCode=code, details=[ResponseDetail(message=msg)])

  @classmethod
  def create_simple_success_response(cls):
    return cls.create_blank_response(ResponseCode.OK, 'OK')

  @classmethod
  def create_error_response(cls):
    return cls.create_blank_response(ResponseCode.ERROR, 'Whoops')

  @classmethod
  def create_mock_api(cls):
    """Builds up a mock API object, with a mock SchedulerProxy"""
    mock_scheduler = create_autospec(spec=SchedulerThriftApiSpec, instance=True)
    mock_scheduler.url = "http://something_or_other"
    mock_scheduler_client = create_autospec(spec=SchedulerProxyApiSpec, instance=True)
    mock_scheduler_client.url = "http://something_or_other"
    mock_api = create_autospec(spec=HookedAuroraClientAPI, instance=True)
    mock_api.scheduler_proxy = mock_scheduler_client
    return mock_api, mock_scheduler_client

  @classmethod
  def create_mock_api_factory(cls):
    """Create a collection of mocks for a test that wants to mock out the client API
    by patching the api factory."""
    mock_api, mock_scheduler_client = cls.create_mock_api()
    mock_api_factory = lambda: mock_api
    return mock_api_factory, mock_scheduler_client

  @classmethod
  def create_query_call_result(cls, task=None):
    status_response = cls.create_empty_task_result()
    if task is None:
      for i in range(20):
        status_response.result.scheduleStatusResult.tasks.append(cls.create_scheduled_task(i))
    else:
      status_response.result.scheduleStatusResult.tasks.append(task)
    return status_response

  @classmethod
  def create_empty_task_result(cls):
    status_response = cls.create_simple_success_response()
    status_response.result = Result(scheduleStatusResult=ScheduleStatusResult(tasks=[]))
    return status_response

  @classmethod
  def create_scheduled_task(cls, instance_id, status=ScheduleStatus.RUNNING,
                            task_id=None, initial_time=None):
    task = ScheduledTask(
        status=status,
        assignedTask=AssignedTask(
            instanceId=instance_id,
            taskId=task_id or "Task%s" % instance_id,
            slaveId="Slave%s" % instance_id,
            slaveHost="Slave%s" % instance_id,
            task=TaskConfig()),
        taskEvents=[TaskEvent(timestamp=initial_time or 1000)])
    return task

  @classmethod
  def create_task_config(cls, name):
    return TaskConfig(
        maxTaskFailures=1,
        executorConfig=ExecutorConfig(data='fake data'),
        metadata=[],
        job=JobKey(role=cls.TEST_ROLE, environment=cls.TEST_ENV, name=name),
        numCpus=2,
        ramMb=2,
        diskMb=2)

  @classmethod
  def create_scheduled_tasks(cls):
    tasks = []
    for name in ['foo', 'bar', 'baz']:
      task = ScheduledTask(
          failureCount=0,
          assignedTask=AssignedTask(
              taskId=1287391823,
              slaveHost='slavehost',
              task=cls.create_task_config(name),
              instanceId=4237894,
              assignedPorts={}),
          status=ScheduleStatus.RUNNING,
          taskEvents=[TaskEvent(
              timestamp=28234726395,
              status=ScheduleStatus.RUNNING,
              message="Hi there")])

      tasks.append(task)
    return tasks

  @classmethod
  def setup_get_tasks_status_calls(cls, scheduler):
    status_response = cls.create_query_call_result()
    scheduler.getTasksWithoutConfigs.return_value = status_response

  @classmethod
  def fake_time(cls, ignored):
    """Utility function used for faking time to speed up tests."""
    cls.FAKE_TIME += 2
    return cls.FAKE_TIME

  CONFIG_BASE = """
HELLO_WORLD = Job(
  name = '%(job)s',
  role = '%(role)s',
  cluster = '%(cluster)s',
  environment = '%(env)s',
  instances = 20,
  %(inner)s
  update_config = UpdateConfig(
    batch_size = 1,
    watch_secs = 45,
    max_per_shard_failures = 2,
  ),
  task = Task(
    name = 'test',
    processes = [Process(name = 'hello_world', cmdline = 'echo {{thermos.ports[http]}}')],
    resources = Resources(cpu = 0.1, ram = 64 * MB, disk = 64 * MB),
  )
)
jobs = [HELLO_WORLD]
"""

  CRON_CONFIG_BASE = """
HELLO_WORLD = Job(
  name = '%(job)s',
  role = '%(role)s',
  cluster = '%(cluster)s',
  environment = '%(env)s',
  cron_schedule = '*/5 * * * *',
  %(inner)s
  task = SimpleTask('test', 'echo test')
)
jobs = [HELLO_WORLD]
"""

  UNBOUND_CONFIG = textwrap.dedent("""\
      HELLO_WORLD = Job(
        name = '%(job)s',
        role = '%(role)s',
        cluster = '{{cluster_binding}}',
        environment = '%(env)s',
        instances = '{{instances_binding}}',
        update_config = UpdateConfig(
          batch_size = "{{TEST_BATCH}}",
          watch_secs = 45,
          max_per_shard_failures = 2,
        ),
        task = Task(
          name = 'test',
          processes = [Process(
            name = 'hello_world',
            cmdline = 'echo {{thermos.ports[http]}} {{flags_binding}}'
          )],
          resources = Resources(cpu = 0.1, ram = 64 * MB, disk = 64 * MB),
        )
      )
      jobs = [HELLO_WORLD]
""")

  TEST_ROLE = 'bozo'

  TEST_ENV = 'test'

  TEST_JOB = 'hello'

  TEST_CLUSTER = 'west'

  TEST_JOBSPEC = 'west/bozo/test/hello'

  TEST_JOBKEY = AuroraJobKey('west', 'bozo', 'test', 'hello')

  TEST_CLUSTERS = Clusters([Cluster(
    name=TEST_CLUSTER,
    zk='zookeeper.example.com',
    scheduler_zk_path='/foo/bar',
    auth_mechanism='UNAUTHENTICATED')])

  @classmethod
  def get_instance_spec(cls, instances_spec):
    """Create a job instance spec string"""
    return '%s/%s' % (cls.TEST_JOBSPEC, instances_spec)

  @classmethod
  def get_test_config(cls, base, cluster, role, env, job, inner=''):
    """Create a config from the template"""
    return base % {'job': job, 'role': role, 'env': env, 'cluster': cluster, 'inner': inner}

  @classmethod
  def get_unbound_test_config(cls, role=None, env=None, job=None):
    result = cls.UNBOUND_CONFIG % {'job': job or cls.TEST_JOB, 'role': role or cls.TEST_ROLE,
        'env': env or cls.TEST_ENV}
    return result

  @classmethod
  def get_valid_config(cls):
    return cls.get_test_config(
        cls.CONFIG_BASE,
        cls.TEST_CLUSTER,
        cls.TEST_ROLE,
        cls.TEST_ENV,
        cls.TEST_JOB)

  @classmethod
  def get_valid_cron_config(cls):
    return cls.get_test_config(
        cls.CRON_CONFIG_BASE,
        cls.TEST_CLUSTER,
        cls.TEST_ROLE,
        cls.TEST_ENV,
        cls.TEST_JOB)

  @classmethod
  def get_invalid_config(cls, bad_clause):
    return cls.get_test_config(
        cls.CONFIG_BASE,
        cls.TEST_CLUSTER,
        cls.TEST_ROLE,
        cls.TEST_ENV,
        cls.TEST_JOB,
        bad_clause)

  @classmethod
  def get_invalid_cron_config(cls, bad_clause):
    return cls.get_test_config(
        cls.CRON_CONFIG_BASE,
        cls.TEST_CLUSTER,
        cls.TEST_ROLE,
        cls.TEST_ENV,
        cls.TEST_JOB,
        bad_clause)

  @classmethod
  def assert_lock_message(cls, context):
    assert [line for line in context.get_err() if line == "\t%s" % context.LOCK_ERROR_MSG]


class IOMock(object):
  def __init__(self):
    self.out = []

  def put(self, s):
    self.out.append(s)

  def get(self):
    return self.out
