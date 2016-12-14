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

import getpass

from apache.aurora.config.schema.base import (
    MB,
    DefaultLifecycleConfig,
    MesosJob,
    MesosTaskInstance,
    Process,
    Resources,
    Task
)

BASE_MTI = MesosTaskInstance(
    instance=0,
    lifecycle=DefaultLifecycleConfig,
    role=getpass.getuser(),
)
BASE_TASK = Task(resources=Resources(cpu=1.0, ram=16 * MB, disk=32 * MB))

HELLO_WORLD_TASK_ID = 'hello_world-001'
HELLO_WORLD = BASE_TASK(
    name='hello_world',
    processes=[Process(name='hello_world_{{thermos.task_id}}', cmdline='echo hello world')])
HELLO_WORLD_MTI = BASE_MTI(task=HELLO_WORLD)

SLEEP60 = BASE_TASK(processes=[Process(name='sleep60', cmdline='sleep 60')])
SLEEP2 = BASE_TASK(processes=[Process(name='sleep2', cmdline='sleep 2')])
SLEEP60_MTI = BASE_MTI(task=SLEEP60)

MESOS_JOB = MesosJob(
  name='does_not_matter',
  instances=1,
  role=getpass.getuser(),
)

HELLO_WORLD_UNBOUND = BASE_TASK(
    name='{{unbound_cmd}}',
    processes=[Process(name='hello_world_{{thermos.task_id}}', cmdline='echo hello {{unbound}}')])
