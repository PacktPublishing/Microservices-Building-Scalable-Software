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

from apache.thermos.config.schema import Constraint, Process, Task
from apache.thermos.testing.runner import RunnerTestBase

from gen.apache.thermos.ttypes import ProcessState, TaskState


class TestRegularFinalizingTask(RunnerTestBase):
  @classmethod
  def task(cls):
    main = Process(name="main", cmdline="date && echo hello world")
    finalizer = Process(name="finalizer", cmdline="date", final=True)
    task = Task(name="task_with_finalizer", processes=[main, finalizer])
    return task.interpolate()[0]

  def test_runner_state(self):
    assert self.state.statuses[-1].state == TaskState.SUCCESS

  def test_runner_process_in_expected_states(self):
    history = self.state.processes
    for process in ('main', 'finalizer'):
      assert len(history[process]) == 1
      assert history[process][0].state == ProcessState.SUCCESS


class TestSequentialFinalizationSchedule(RunnerTestBase):
  @classmethod
  def task(cls):
    main = Process(name="main", cmdline="date && echo hello world")
    finalizer = Process(name="finalizer", cmdline="date", final=True)
    task = Task(name="task_with_finalizer",
                processes=[main, finalizer(name='finalizer1'), finalizer(name='finalizer2')],
                constraints=[Constraint(order=['finalizer1', 'finalizer2'])])
    return task.interpolate()[0]

  def test_runner_state(self):
    assert self.state.statuses[-1].state == TaskState.SUCCESS

  def test_runner_process_in_expected_states(self):
    history = self.state.processes
    for process in ('main', 'finalizer1', 'finalizer2'):
      assert len(history[process]) == 1
      assert history[process][0].state == ProcessState.SUCCESS
    assert history['main'][0].stop_time < history['finalizer1'][0].start_time
    assert history['finalizer1'][0].stop_time < history['finalizer2'][0].start_time


class TestTaskSucceedsDespiteFinalizationFailure(RunnerTestBase):
  @classmethod
  def task(cls):
    main = Process(name="main", cmdline="date && echo hello world")
    finalizer = Process(name="finalizer", cmdline="date", final=True)
    task = Task(name="task_with_finalizer",
                processes=[
                  main,
                  finalizer(name='finalizer1', cmdline='exit 1'),
                  finalizer(name='finalizer2')],
                constraints=[Constraint(order=['finalizer1', 'finalizer2'])])
    return task.interpolate()[0]

  def test_runner_state(self):
    assert self.state.statuses[-1].state == TaskState.SUCCESS

  def test_runner_process_in_expected_states(self):
    history = self.state.processes
    assert len(history['main']) == 1
    assert history['main'][0].state == ProcessState.SUCCESS
    assert len(history['finalizer1']) == 1
    assert history['finalizer1'][0].state == ProcessState.FAILED
    assert 'finalizer2' not in history


class TestParallelFinalizationFailure(RunnerTestBase):
  @classmethod
  def task(cls):
    main = Process(name="main", cmdline="echo hello world")
    finalizer = Process(cmdline="date", final=True)
    task = Task(name="task_with_finalizer",
                processes=[main,
                           finalizer(name='finalizer1', cmdline='exit 1', max_failures=2),
                           finalizer(name='finalizer2')])
    return task.interpolate()[0]

  def test_runner_state(self):
    assert self.state.statuses[-1].state == TaskState.SUCCESS

  def test_runner_process_in_expected_states(self):
    history = self.state.processes
    assert len(history['main']) == 1
    assert history['main'][0].state == ProcessState.SUCCESS
    assert len(history['finalizer1']) == 2
    assert history['finalizer1'][0].state == ProcessState.FAILED
    assert history['finalizer1'][1].state == ProcessState.FAILED
    assert len(history['finalizer2']) == 1
    assert history['finalizer2'][0].state == ProcessState.SUCCESS


class TestFinalizationRunsDespiteFailure(RunnerTestBase):
  @classmethod
  def task(cls):
    main = Process(name="main", cmdline="exit 1")
    finalizer = Process(name="finalizer", cmdline="date", final=True)
    task = Task(name="task_with_finalizer", processes=[main, finalizer])
    return task.interpolate()[0]

  def test_runner_state(self):
    assert self.state.statuses[-1].state == TaskState.FAILED

  def test_runner_process_in_expected_states(self):
    history = self.state.processes
    assert len(history['main']) == 1
    assert history['main'][0].state == ProcessState.FAILED
    assert len(history['finalizer']) == 1
    assert history['finalizer'][0].state == ProcessState.SUCCESS
