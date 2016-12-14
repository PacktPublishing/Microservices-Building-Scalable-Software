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

import contextlib

from mock import Mock, patch

from apache.aurora.client.cli import EXIT_INVALID_PARAMETER, EXIT_OK
from apache.aurora.client.cli.client import AuroraCommandLine

from .util import AuroraClientCommandTest

from gen.apache.aurora.api.ttypes import (
    JobKey,
    ResponseCode,
    Result,
    ScheduleStatus,
    ScheduleStatusResult,
    TaskQuery
)


class TestRunCommand(AuroraClientCommandTest):

  @classmethod
  def create_status_response(cls):
    resp = cls.create_simple_success_response()
    resp.result = Result(
        scheduleStatusResult=ScheduleStatusResult(tasks=cls.create_scheduled_tasks()))
    return resp

  @classmethod
  def create_failed_status_response(cls):
    return cls.create_blank_response(ResponseCode.INVALID_REQUEST, 'No tasks found for query')

  @classmethod
  def create_mock_process(cls):
    process = Mock()
    process.communicate.return_value = ["hello", "world"]
    return process

  def test_successful_run(self):
    """Test the run command."""
    self.generic_test_successful_run(['task', 'run', 'west/bozo/test/hello', 'ls'], None)

  def test_successful_run_with_instances(self):
    """Test the run command."""
    self.generic_test_successful_run(['task', 'run', 'west/bozo/test/hello/1-3', 'ls'], [1, 2, 3])

  def test_successful_run_with_ssh_options(self):
    self.generic_test_successful_run(
        ['task', 'run', '--ssh-options=-v -k', 'west/bozo/test/hello', 'ls'],
        None,
        ssh_options=['-v', '-k'])

  def generic_test_successful_run(self, cmd_args, instances, ssh_options=None):
    """Common structure of all successful run tests.
    Params:
      cmd_args: the arguments to pass to the aurora command line to run this test.
      instances: the list of instances that should be passed to a status query.
         (The status query is the only visible difference between a sharded
         run, and an all-instances run in the test.)
    """
    # Calls api.check_status, which calls scheduler_proxy.getJobs
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_status_response()
    sandbox_args = {'slave_root': '/slaveroot', 'slave_run_directory': 'slaverun'}
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('apache.aurora.client.api.command_runner.'
              'InstanceDistributedCommandRunner.sandbox_args',
            return_value=sandbox_args),
        patch('subprocess.Popen', return_value=self.create_mock_process())) as (
            mock_scheduler_proxy_class,
            mock_runner_args_patch,
            mock_subprocess):
      cmd = AuroraCommandLine()
      assert cmd.execute(cmd_args) == EXIT_OK
      # The status command sends a getTasksStatus query to the scheduler,
      # and then prints the result. The use of shards, above, should change
      # this query - that's the focus of the instances test.
      mock_scheduler_proxy.getTasksStatus.assert_called_with(TaskQuery(
          jobKeys=[JobKey(role='bozo', environment='test', name='hello')],
          statuses=set([ScheduleStatus.RUNNING, ScheduleStatus.KILLING, ScheduleStatus.RESTARTING,
              ScheduleStatus.PREEMPTING, ScheduleStatus.DRAINING]),
          instanceIds=instances))

      # The mock status call returns 3 three ScheduledTasks, so three commands should have been run
      assert mock_subprocess.call_count == 3
      expected = ['ssh', '-n', '-q']
      expected += ssh_options if ssh_options else []
      expected += ['bozo@slavehost',
          'cd /slaveroot/slaves/*/frameworks/*/executors/thermos-1287391823/runs/'
          'slaverun/sandbox;ls']
      mock_subprocess.assert_called_with(expected, stderr=-2, stdout=-1)


class TestSshCommand(AuroraClientCommandTest):

  @classmethod
  def create_status_response(cls):
    resp = cls.create_simple_success_response()
    resp.result = Result(
        scheduleStatusResult=ScheduleStatusResult(tasks=cls.create_scheduled_tasks()))
    return resp

  @classmethod
  def create_nojob_status_response(cls):
    resp = cls.create_simple_success_response()
    resp.result = Result(scheduleStatusResult=ScheduleStatusResult(tasks=[]))
    return resp

  @classmethod
  def create_failed_status_response(cls):
    return cls.create_blank_response(ResponseCode.INVALID_REQUEST, 'No tasks found for query')

  def test_successful_ssh(self):
    """Test the ssh command."""
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_status_response()
    sandbox_args = {'slave_root': '/slaveroot', 'slave_run_directory': 'slaverun'}
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('apache.aurora.client.api.command_runner.DistributedCommandRunner.sandbox_args',
            return_value=sandbox_args),
        patch('subprocess.call', return_value=0)) as (
            mock_scheduler_proxy_class,
            mock_runner_args_patch,
            mock_subprocess):
      cmd = AuroraCommandLine()
      cmd.execute(['task', 'ssh', '--ssh-options=-v', 'west/bozo/test/hello/1', '--command=ls'])

      # The status command sends a getTasksStatus query to the scheduler,
      # and then prints the result.
      mock_scheduler_proxy.getTasksStatus.assert_called_with(TaskQuery(
          jobKeys=[JobKey(role='bozo', environment='test', name='hello')],
          instanceIds=set([1]),
          statuses=set([ScheduleStatus.RUNNING, ScheduleStatus.KILLING, ScheduleStatus.RESTARTING,
              ScheduleStatus.PREEMPTING, ScheduleStatus.DRAINING])))
      mock_subprocess.assert_called_with(['ssh', '-t', '-v', 'bozo@slavehost',
          'cd /slaveroot/slaves/*/frameworks/*/executors/thermos-1287391823/runs/'
          'slaverun/sandbox;ls'])

  def test_ssh_job_not_found(self):
    """Test the ssh command when the jobkey parameter specifies a job that isn't running."""
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_nojob_status_response()
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('subprocess.call', return_value=0)) as (
            mock_scheduler_proxy_class,
            mock_subprocess):
      cmd = AuroraCommandLine()
      result = cmd.execute(['task', 'ssh', 'west/bozo/test/hello/1', '--command=ls'])
      assert result == EXIT_INVALID_PARAMETER
      assert mock_subprocess.call_count == 0
