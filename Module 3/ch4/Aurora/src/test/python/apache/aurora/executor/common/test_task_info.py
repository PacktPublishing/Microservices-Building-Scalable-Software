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

import pytest

from apache.aurora.config.schema.base import Process
from apache.aurora.executor.common.task_info import (
    TaskInfoError,
    UnexpectedUnboundRefsError,
    mesos_task_instance_from_assigned_task
)

from .fixtures import (
    BASE_MTI,
    BASE_TASK,
    HELLO_WORLD,
    HELLO_WORLD_MTI,
    HELLO_WORLD_UNBOUND,
    MESOS_JOB
)

from gen.apache.aurora.api.ttypes import AssignedTask, ExecutorConfig, TaskConfig


def test_deserialize_thermos_task():
  task_config = TaskConfig(
      executorConfig=ExecutorConfig(name='thermos', data=MESOS_JOB(task=HELLO_WORLD).json_dumps()))
  assigned_task = AssignedTask(task=task_config, instanceId=0)
  assert mesos_task_instance_from_assigned_task(assigned_task) == BASE_MTI(task=HELLO_WORLD)

  task_config = TaskConfig(
    executorConfig=ExecutorConfig(name='thermos', data=HELLO_WORLD_MTI.json_dumps()))
  assigned_task = AssignedTask(task=task_config, instanceId=0)
  assert mesos_task_instance_from_assigned_task(assigned_task) == BASE_MTI(task=HELLO_WORLD)


def test_deserialize_thermos_task_unbound_refs():
  # test unbound {{standard}} refs
  task_config = TaskConfig(
      executorConfig=ExecutorConfig(
        name='thermos', data=MESOS_JOB(task=HELLO_WORLD_UNBOUND).json_dumps()))
  assigned_task = AssignedTask(task=task_config, instanceId=0)
  with pytest.raises(TaskInfoError) as execinfo:
    mesos_task_instance_from_assigned_task(assigned_task)

  assert "Unexpected unbound refs: {{unbound_cmd}} {{unbound}}" in execinfo.value.message

  # test bound unscoped refs, valid case.
  task = BASE_TASK(
      name='task_name',
      processes=[Process(name='process_name', cmdline='echo {{thermos.ports[health]}}')])
  task_config = TaskConfig(
      executorConfig=ExecutorConfig(name='thermos', data=MESOS_JOB(task=task).json_dumps()))
  assigned_task = AssignedTask(task=task_config, instanceId=0)
  assert mesos_task_instance_from_assigned_task(assigned_task) is not None

  # test unbound unscoped refs
  for cmdline in (
      'echo {{hello_{{thermos.ports[health]}}}}',
      'echo {{hello_{{thermos.user_id}}}}'):
    task = BASE_TASK(name='task_name', processes=[Process(name='process_name', cmdline=cmdline)])
    task_config = TaskConfig(
        executorConfig=ExecutorConfig(name='thermos', data=MESOS_JOB(task=task).json_dumps()))
    assigned_task = AssignedTask(task=task_config, instanceId=0)

    with pytest.raises(UnexpectedUnboundRefsError):
      mesos_task_instance_from_assigned_task(assigned_task)
