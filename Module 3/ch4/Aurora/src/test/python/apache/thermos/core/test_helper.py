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

import time

import mock
import psutil
from twitter.common.quantity import Time

from apache.thermos.core.helper import TaskRunnerHelper as TRH

from gen.apache.thermos.ttypes import ProcessStatus, RunnerHeader, RunnerState

USER1 = 'user1'
UID = 567
PID = 12345
CREATE_TIME = time.time()
PROCESS_NAME = 'my_process'
COORDINATOR_PID = 13337


def set_side_effect(mock_obj, value):
  if isinstance(value, Exception):
    mock_obj.side_effect = value
  else:
    mock_obj.return_value = value


def mock_process(pid, username, uid=None):
  process = mock.create_autospec(spec=psutil.Process, pid=pid, instance=True)
  set_side_effect(process.uids, uid)
  set_side_effect(process.username, username)
  process.create_time.return_value = CREATE_TIME
  return process


def test_this_is_really_our_pid():
  process = mock_process(PID, USER1, uid=UID)
  assert TRH.this_is_really_our_pid(process, UID, USER1, process.create_time())
  assert TRH.this_is_really_our_pid(process, UID, USER1,
      process.create_time() + TRH.MAX_START_TIME_DRIFT.as_(Time.SECONDS) - 1)
  assert TRH.this_is_really_our_pid(process, UID, 'user2', process.create_time()), (
      'An equivalent UID is considered the same user.')
  assert not TRH.this_is_really_our_pid(process, UID + 1, USER1, process.create_time()), (
      'UIDs should not match.')
  assert not TRH.this_is_really_our_pid(process, UID, USER1,
      process.create_time() + TRH.MAX_START_TIME_DRIFT.as_(Time.SECONDS) + 1), (
          'Process is outside of start time drift.')
  assert not TRH.this_is_really_our_pid(process, UID, USER1,
      process.create_time() - (TRH.MAX_START_TIME_DRIFT.as_(Time.SECONDS) + 1)), (
          'Process is outside of start time drift.')
  assert not TRH.this_is_really_our_pid(process, None, 'user2', process.create_time()), (
      "If no uid is checkpointed but the username is different, we can't know it's ours.")

  process = mock_process(PID, USER1)
  assert not TRH.this_is_really_our_pid(process, UID, USER1, process.create_time()), (
      'We cannot validate whether this is our process without a process UID.')
  assert TRH.this_is_really_our_pid(process, None, USER1, process.create_time()), (
      'Previous behavior is preserved.')

  process = mock_process(PID, username=KeyError('Unknown user'), uid=UID)
  assert TRH.this_is_really_our_pid(process, UID, USER1, process.create_time())
  assert not TRH.this_is_really_our_pid(process, None, USER1, process.create_time())
  assert TRH.this_is_really_our_pid(process, UID, 'user2', process.create_time()), (
      'If the user has been renamed but the UID is the same, this is still our process.')


TRH_PATH = 'apache.thermos.core.helper.TaskRunnerHelper'
PSUTIL_PATH = 'psutil.Process'


def make_runner_state(cpid=COORDINATOR_PID, pid=PID, user=USER1, pname=PROCESS_NAME):
  return RunnerState(
    header=RunnerHeader(user=user),
    processes={
      pname: [
        ProcessStatus(
          fork_time=CREATE_TIME,
          start_time=CREATE_TIME,
          pid=pid,
          coordinator_pid=cpid,
          process=pname)
      ]
    }
  )


def test_scan_process():
  # TODO(jon): add more tests for successful cases; this really just looks for errors.

  assert TRH.scan_process(
      make_runner_state(cpid=None, pid=None), PROCESS_NAME) == (None, None, set())

  with mock.patch(PSUTIL_PATH) as p_mock:
    class WrappedNoSuchProcess(psutil.NoSuchProcess):
      # psutil.NoSuchProcess exception requires an argument, but mock doesn't support that.

      def __init__(self):
        pass

    p_mock.side_effect = WrappedNoSuchProcess
    assert TRH.scan_process(
        make_runner_state(cpid=None), PROCESS_NAME) == (None, None, set())
    assert TRH.scan_process(
        make_runner_state(pid=None), PROCESS_NAME) == (None, None, set())

  with mock.patch(PSUTIL_PATH) as p_mock:
    p_mock.side_effect = psutil.Error
    assert TRH.scan_process(
        make_runner_state(cpid=None), PROCESS_NAME) == (None, None, set())
    assert TRH.scan_process(
        make_runner_state(pid=None), PROCESS_NAME) == (None, None, set())

  with mock.patch(TRH_PATH) as trh_mock:
    trh_mock.this_is_really_our_pid.returns = False
    assert TRH.scan_process(
        make_runner_state(), PROCESS_NAME) == (None, None, set())

  with mock.patch(TRH_PATH) as trh_mock:
    trh_mock.this_is_really_our_pid.sideffect = psutil.Error
    assert TRH.scan_process(
        make_runner_state(), PROCESS_NAME) == (None, None, set())
