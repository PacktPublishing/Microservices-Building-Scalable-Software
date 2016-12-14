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

import json
import os.path
import threading
import time
import unittest

import mock
import pytest
from mesos.interface.mesos_pb2 import TaskState
from twitter.common.exceptions import ExceptionalThread
from twitter.common.testing.clock import ThreadedClock

from apache.aurora.common.health_check.http_signaler import HttpSignaler
from apache.aurora.config.schema.base import (
    HealthCheckConfig,
    HealthCheckerConfig,
    HttpHealthChecker,
    ShellHealthChecker
)
from apache.aurora.executor.common.health_checker import (
    HealthChecker,
    HealthCheckerProvider,
    ThreadedHealthChecker
)
from apache.aurora.executor.common.sandbox import SandboxInterface

from .fixtures import HELLO_WORLD, MESOS_JOB

from gen.apache.aurora.api.ttypes import AssignedTask, ExecutorConfig, JobKey, TaskConfig


class TestHealthChecker(unittest.TestCase):
  def setUp(self):
    self._clock = ThreadedClock(0)
    self._checker = mock.Mock(spec=HttpSignaler)

    self.fake_health_checks = []
    def mock_health_check():
      return self.fake_health_checks.pop(0)
    self._checker.health = mock.Mock(spec=self._checker.__call__)
    self._checker.health.side_effect = mock_health_check

  def append_health_checks(self, status, num_calls=1):
    for i in range(num_calls):
      self.fake_health_checks.append((status, 'reason'))

  def test_initial_interval_2x(self):
    self.append_health_checks(False)
    hct = HealthChecker(self._checker.health, interval_secs=5, clock=self._clock)
    hct.start()
    assert self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, 10)
    assert hct.status is None
    self._clock.tick(6)
    assert self._clock.converge(threads=[hct.threaded_health_checker])
    assert hct.status is None
    self._clock.tick(3)
    assert self._clock.converge(threads=[hct.threaded_health_checker])
    assert hct.status is None
    self._clock.tick(5)
    assert self._clock.converge(threads=[hct.threaded_health_checker])
    assert hct.status.status == TaskState.Value('TASK_FAILED')
    hct.stop()
    assert self._checker.health.call_count == 1

  def test_initial_interval_whatev(self):
    self.append_health_checks(False, 2)
    hct = HealthChecker(
        self._checker.health,
        interval_secs=5,
        initial_interval_secs=0,
        clock=self._clock)
    hct.start()
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=5)
    assert hct.status.status == TaskState.Value('TASK_FAILED')
    hct.stop()
    # this is an implementation detail -- we healthcheck in the initializer and
    # healthcheck in the run loop.  if we ever change the implementation, expect
    # this to break.
    assert self._checker.health.call_count == 2

  def test_consecutive_failures(self):
    '''Verify that a task is unhealthy only after max_consecutive_failures is exceeded'''
    initial_interval_secs = 2
    interval_secs = 1
    self.append_health_checks(False, num_calls=2)
    self.append_health_checks(True)
    self.append_health_checks(False, num_calls=3)
    hct = HealthChecker(
        self._checker.health,
        interval_secs=interval_secs,
        initial_interval_secs=initial_interval_secs,
        max_consecutive_failures=2,
        clock=self._clock)
    hct.start()
    self._clock.converge(threads=[hct.threaded_health_checker])

    # 2 consecutive health check failures followed by a successful health check.
    epsilon = 0.001
    self._clock.tick(initial_interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status is None
    assert hct.metrics.sample()['consecutive_failures'] == 1
    self._clock.tick(interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status is None
    assert hct.metrics.sample()['consecutive_failures'] == 2
    self._clock.tick(interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status is None
    assert hct.metrics.sample()['consecutive_failures'] == 0

    # 3 consecutive health check failures.
    self._clock.tick(interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status is None
    assert hct.metrics.sample()['consecutive_failures'] == 1
    self._clock.tick(interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status is None
    assert hct.metrics.sample()['consecutive_failures'] == 2
    self._clock.tick(interval_secs + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)
    assert hct.status.status == TaskState.Value('TASK_FAILED')
    assert hct.metrics.sample()['consecutive_failures'] == 3
    hct.stop()
    assert self._checker.health.call_count == 6

  @pytest.mark.skipif('True', reason='Flaky test (AURORA-1182)')
  def test_health_checker_metrics(self):
    def slow_check():
      self._clock.sleep(0.5)
      return (True, None)
    hct = HealthChecker(slow_check, interval_secs=1, initial_interval_secs=1, clock=self._clock)
    hct.start()
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)

    assert hct._total_latency == 0
    assert hct.metrics.sample()['total_latency_secs'] == 0

    # start the health check (during health check it is still 0)
    epsilon = 0.001
    self._clock.tick(1.0 + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=0.5)
    assert hct._total_latency == 0
    assert hct.metrics.sample()['total_latency_secs'] == 0
    assert hct.metrics.sample()['checks'] == 0

    # finish the health check
    self._clock.tick(0.5 + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)  # interval_secs
    assert hct._total_latency == 0.5
    assert hct.metrics.sample()['total_latency_secs'] == 0.5
    assert hct.metrics.sample()['checks'] == 1

    # tick again
    self._clock.tick(1.0 + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.tick(0.5 + epsilon)
    self._clock.converge(threads=[hct.threaded_health_checker])
    self._clock.assert_waiting(hct.threaded_health_checker, amount=1)  # interval_secs
    assert hct._total_latency == 1.0
    assert hct.metrics.sample()['total_latency_secs'] == 1.0
    assert hct.metrics.sample()['checks'] == 2


class TestHealthCheckerProvider(unittest.TestCase):
  def test_from_assigned_task_http(self):
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    task_config = TaskConfig(
        executorConfig=ExecutorConfig(
            name='thermos',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=7
                )
            ).json_dumps()
        )
    )
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'health': 9001})
    health_checker = HealthCheckerProvider().from_assigned_task(assigned_task, None)
    assert health_checker.threaded_health_checker.interval == interval_secs
    assert health_checker.threaded_health_checker.initial_interval == initial_interval_secs
    hct_max_fail = health_checker.threaded_health_checker.max_consecutive_failures
    assert hct_max_fail == max_consecutive_failures

  def test_from_assigned_task_http_endpoint_style_config(self):
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    http_config = HttpHealthChecker(
      endpoint='/foo',
      expected_response='bar',
      expected_response_code=201
    )
    task_config = TaskConfig(
        executorConfig=ExecutorConfig(
            name='thermos',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    health_checker=HealthCheckerConfig(http=http_config),
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=7
                )
            ).json_dumps()
        )
    )
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'health': 9001})
    execconfig_data = json.loads(assigned_task.task.executorConfig.data)
    http_exec_config = execconfig_data['health_check_config']['health_checker']['http']
    assert http_exec_config['endpoint'] == '/foo'
    assert http_exec_config['expected_response'] == 'bar'
    assert http_exec_config['expected_response_code'] == 201
    health_checker = HealthCheckerProvider().from_assigned_task(assigned_task, None)
    assert health_checker.threaded_health_checker.interval == interval_secs
    assert health_checker.threaded_health_checker.initial_interval == initial_interval_secs

  @mock.patch('pwd.getpwnam')
  def test_from_assigned_task_shell(self, mock_getpwnam):
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    timeout_secs = 5
    shell_config = ShellHealthChecker(shell_command='failed command')
    task_config = TaskConfig(
        job=JobKey(role='role', environment='env', name='name'),
        executorConfig=ExecutorConfig(
            name='thermos-generic',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    health_checker=HealthCheckerConfig(shell=shell_config),
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=timeout_secs,
                )
            ).json_dumps()
        )
    )
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'foo': 9001})
    execconfig_data = json.loads(assigned_task.task.executorConfig.data)
    assert execconfig_data[
             'health_check_config']['health_checker']['shell']['shell_command'] == 'failed command'
    health_checker = HealthCheckerProvider().from_assigned_task(assigned_task, None)
    assert health_checker.threaded_health_checker.interval == interval_secs
    assert health_checker.threaded_health_checker.initial_interval == initial_interval_secs
    hct_max_fail = health_checker.threaded_health_checker.max_consecutive_failures
    assert hct_max_fail == max_consecutive_failures
    mock_getpwnam.assert_called_once_with(task_config.job.role)

  @mock.patch('pwd.getpwnam')
  def test_from_assigned_task_shell_no_demotion(self, mock_getpwnam):
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    timeout_secs = 5
    shell_config = ShellHealthChecker(shell_command='failed command')
    task_config = TaskConfig(
        job=JobKey(role='role', environment='env', name='name'),
        executorConfig=ExecutorConfig(
            name='thermos-generic',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    health_checker=HealthCheckerConfig(shell=shell_config),
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=timeout_secs,
                )
            ).json_dumps()
        )
    )
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'foo': 9001})
    execconfig_data = json.loads(assigned_task.task.executorConfig.data)
    assert execconfig_data[
             'health_check_config']['health_checker']['shell']['shell_command'] == 'failed command'
    health_checker = HealthCheckerProvider(nosetuid_health_checks=True).from_assigned_task(
      assigned_task, None)
    assert health_checker.threaded_health_checker.interval == interval_secs
    assert health_checker.threaded_health_checker.initial_interval == initial_interval_secs
    hct_max_fail = health_checker.threaded_health_checker.max_consecutive_failures
    assert hct_max_fail == max_consecutive_failures
    # Should not be trying to access role's user info.
    assert not mock_getpwnam.called

  def test_interpolate_cmd(self):
    """Making sure thermos.ports[foo] gets correctly substituted with assignedPorts info."""
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    timeout_secs = 5
    shell_cmd = 'FOO_PORT={{thermos.ports[foo]}} failed command'
    shell_config = ShellHealthChecker(shell_command=shell_cmd)
    task_config = TaskConfig(
        executorConfig=ExecutorConfig(
            name='thermos-generic',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    health_checker=HealthCheckerConfig(shell=shell_config),
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=timeout_secs,
                )
            ).json_dumps()
        )
    )
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'foo': 9001})
    interpolated_cmd = HealthCheckerProvider.interpolate_cmd(
      assigned_task,
      cmd=shell_cmd
    )
    assert interpolated_cmd == 'FOO_PORT=9001 failed command'

  def test_from_assigned_task_no_health_port(self):
    interval_secs = 17
    initial_interval_secs = 3
    max_consecutive_failures = 2
    timeout_secs = 5
    task_config = TaskConfig(
        executorConfig=ExecutorConfig(
            name='thermos-generic',
            data=MESOS_JOB(
                task=HELLO_WORLD,
                health_check_config=HealthCheckConfig(
                    interval_secs=interval_secs,
                    initial_interval_secs=initial_interval_secs,
                    max_consecutive_failures=max_consecutive_failures,
                    timeout_secs=timeout_secs,
                )
            ).json_dumps()
        )
    )
    # No health port and we don't have a shell_command.
    assigned_task = AssignedTask(task=task_config, instanceId=1, assignedPorts={'http': 9001})
    health_checker = HealthCheckerProvider().from_assigned_task(assigned_task, None)
    self.assertIsNone(health_checker)


class TestThreadedHealthChecker(unittest.TestCase):
  def setUp(self):
    self.health = mock.Mock()
    self.health.return_value = (True, 'Fake')

    self.sandbox = mock.Mock(spec_set=SandboxInterface)
    self.sandbox.exists.return_value = True
    self.sandbox.root = '/root'

    self.initial_interval_secs = 1
    self.interval_secs = 5
    self.max_consecutive_failures = 2
    self.clock = mock.Mock(spec=time)
    self.clock.time.return_value = 1.0
    self.health_checker = HealthChecker(
        self.health,
        None,
        self.interval_secs,
        self.initial_interval_secs,
        self.max_consecutive_failures,
        self.clock)
    self.health_checker_sandbox_exists = HealthChecker(
        self.health,
        self.sandbox,
        self.interval_secs,
        self.initial_interval_secs,
        self.max_consecutive_failures,
        self.clock)

  def test_perform_check_if_not_disabled_snooze_file_is_none(self):
    self.health_checker.threaded_health_checker.snooze_file = None
    assert self.health.call_count == 0
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 0
    self.health_checker.threaded_health_checker._perform_check_if_not_disabled()
    assert self.health.call_count == 1
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 0

  @mock.patch('os.path', spec_set=os.path)
  def test_perform_check_if_not_disabled_no_snooze_file(self, mock_os_path):
    mock_os_path.isfile.return_value = False
    assert self.health.call_count == 0
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 0
    self.health_checker_sandbox_exists.threaded_health_checker._perform_check_if_not_disabled()
    assert self.health.call_count == 1
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 0

  @mock.patch('os.path', spec_set=os.path)
  def test_perform_check_if_not_disabled_snooze_file_exists(self, mock_os_path):
    mock_os_path.isfile.return_value = True
    assert self.health.call_count == 0
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 0
    result = (
        self.health_checker_sandbox_exists.threaded_health_checker._perform_check_if_not_disabled())
    assert self.health.call_count == 0
    assert self.health_checker_sandbox_exists.metrics.sample()['snoozed'] == 1
    assert result == (True, None)

  def test_maybe_update_failure_count(self):
    hc = self.health_checker.threaded_health_checker

    assert hc.current_consecutive_failures == 0
    assert hc.healthy is True

    hc._maybe_update_failure_count(True, 'reason')
    assert hc.current_consecutive_failures == 0

    hc._maybe_update_failure_count(False, 'reason')
    assert hc.current_consecutive_failures == 1
    assert hc.healthy is True

    hc._maybe_update_failure_count(False, 'reason')
    assert hc.current_consecutive_failures == 2
    assert hc.healthy is True

    hc._maybe_update_failure_count(False, 'reason')
    assert hc.healthy is False
    assert hc.reason == 'reason'

  @mock.patch('apache.aurora.executor.common.health_checker.ThreadedHealthChecker'
      '._maybe_update_failure_count',
      spec=ThreadedHealthChecker._maybe_update_failure_count)
  def test_run(self, mock_maybe_update_failure_count):
    mock_is_set = mock.Mock(spec=threading._Event.is_set)
    self.health_checker.threaded_health_checker.dead.is_set = mock_is_set
    liveness = [False, False, True]
    self.health_checker.threaded_health_checker.dead.is_set.side_effect = lambda: liveness.pop(0)
    self.health_checker.threaded_health_checker.run()
    assert self.clock.sleep.call_count == 3
    assert mock_maybe_update_failure_count.call_count == 2

  @mock.patch('apache.aurora.executor.common.health_checker.ExceptionalThread.start',
      spec=ExceptionalThread.start)
  def test_start(self, mock_start):
    assert mock_start.call_count == 0
    self.health_checker.threaded_health_checker.start()
    mock_start.assert_called_once_with(self.health_checker.threaded_health_checker)

  def test_stop(self):
    assert not self.health_checker.threaded_health_checker.dead.is_set()
    self.health_checker.threaded_health_checker.stop()
    assert self.health_checker.threaded_health_checker.dead.is_set()
