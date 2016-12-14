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
import re

from pystachio import Empty, Ref
from twitter.common.lang import Compatibility

from apache.aurora.config.schema.base import HealthCheckConfig, MesosContext, MesosTaskInstance
from apache.thermos.config.loader import ThermosTaskValidator

from gen.apache.aurora.api.constants import AURORA_EXECUTOR_NAME, GOOD_IDENTIFIER_PATTERN_PYTHON
from gen.apache.aurora.api.ttypes import (
    Constraint,
    Container,
    CronCollisionPolicy,
    DockerContainer,
    DockerParameter,
    ExecutorConfig,
    Identity,
    JobConfiguration,
    JobKey,
    LimitConstraint,
    MesosContainer,
    Metadata,
    TaskConfig,
    TaskConstraint,
    ValueConstraint
)

__all__ = (
  'InvalidConfig',
  'convert'
)


class InvalidConfig(ValueError):
  pass


def constraints_to_thrift(constraints):
  """Convert a python dictionary to a set of Constraint thrift objects."""
  result = set()
  for attribute, constraint_value in constraints.items():
    assert isinstance(attribute, Compatibility.string) and (
        isinstance(constraint_value, Compatibility.string)), (
            "Both attribute name and value in constraints must be string")
    constraint = Constraint()
    constraint.name = attribute
    task_constraint = TaskConstraint()
    if constraint_value.startswith('limit:'):
      task_constraint.limit = LimitConstraint()
      try:
        task_constraint.limit.limit = int(constraint_value.replace('limit:', '', 1))
      except ValueError:
        print('%s is not a valid limit value, must be integer' % constraint_value)
        raise
    else:
      # Strip off the leading negation if present.
      negated = constraint_value.startswith('!')
      if negated:
        constraint_value = constraint_value[1:]
      task_constraint.value = ValueConstraint(negated, set(constraint_value.split(',')))
    constraint.constraint = task_constraint
    result.add(constraint)
  return result


def task_instance_from_job(job, instance, hostname):
  instance_context = MesosContext(instance=instance, hostname=hostname)
  health_check_config = HealthCheckConfig()
  if job.has_health_check_config():
    health_check_config = job.health_check_config()
  ti = MesosTaskInstance(task=job.task(),
                         role=job.role(),
                         health_check_config=health_check_config,
                         instance=instance)
  if job.has_announce():
    ti = ti(announce=job.announce())
  if job.has_environment():
    ti = ti(environment=job.environment())
  if job.has_lifecycle():
    ti = ti(lifecycle=job.lifecycle())
  return ti.bind(mesos=instance_context)


def fully_interpolated(pystachio_object, coerce_fn=lambda i: i):
  # Extract a fully-interpolated unwrapped object from pystachio_object or raise InvalidConfig.
  #
  # TODO(ksweeney): Remove this once Pystachio 1.0 changes the behavior of interpolate() to return
  # unwrapped objects and fail when there are any unbound refs.
  if not pystachio_object.check().ok():
    raise InvalidConfig(pystachio_object.check().message())

  # If an object type-checks it's okay to use the raw value from the wrapped object returned by
  # interpolate. Without the previous check value.get() could return a string with mustaches
  # instead of an object of the expected type.
  value, _ = pystachio_object.interpolate()
  return coerce_fn(value.get())


def parse_enum(enum_type, value):
  enum_value = enum_type._NAMES_TO_VALUES.get(value.get().upper())
  if enum_value is None:
    raise InvalidConfig('Invalid %s type: %s' % (enum_type, value.get()))
  return enum_value


def select_cron_policy(cron_policy):
  return parse_enum(CronCollisionPolicy, cron_policy)


def select_service_bit(job):
  return fully_interpolated(job.service(), bool)


def create_container_config(container):
  if container is Empty:
    return Container(MesosContainer(), None)
  elif container.docker() is not Empty:
    params = list()
    if container.docker().parameters() is not Empty:
      for p in fully_interpolated(container.docker().parameters()):
        params.append(DockerParameter(p['name'], p['value']))
    return Container(None, DockerContainer(fully_interpolated(container.docker().image()), params))
  else:
    raise InvalidConfig('If a container is specified it must set one type.')


# TODO(wickman): We should revert to using the MesosTaskInstance.
#
# Using the MesosJob instead of the MesosTaskInstance was to allow for
# planned future use of fields such as 'cluster' and to allow for conversion
# from Job=>Task to be done entirely on the executor, but instead this had
# made it impossible to run idempotent updates.
#
# In the meantime, we are erasing fields of the Job that are controversial.
# This achieves roughly the same effect as using the MesosTaskInstance.
ALIASED_FIELDS = (
  'update_config',
  'instances'
)


def filter_aliased_fields(job):
  return job(**dict((key, Empty) for key in ALIASED_FIELDS))


def assert_valid_field(field, identifier):
  VALID_IDENTIFIER = re.compile(GOOD_IDENTIFIER_PATTERN_PYTHON)
  if not isinstance(identifier, Compatibility.string):
    raise InvalidConfig("%s must be a string" % field)
  if not VALID_IDENTIFIER.match(identifier):
    raise InvalidConfig("Invalid %s '%s'" % (field, identifier))
  return identifier


MESOS_INSTANCE_REF = Ref.from_address('mesos.instance')
MESOS_HOSTNAME_REF = Ref.from_address('mesos.hostname')
THERMOS_PORT_SCOPE_REF = Ref.from_address('thermos.ports')
THERMOS_TASK_ID_REF = Ref.from_address('thermos.task_id')


def convert(job, metadata=frozenset(), ports=frozenset()):
  """Convert a Pystachio MesosJob to an Aurora Thrift JobConfiguration."""

  owner = Identity(user=getpass.getuser())
  key = JobKey(
    role=assert_valid_field('role', fully_interpolated(job.role())),
    environment=assert_valid_field('environment', fully_interpolated(job.environment())),
    name=assert_valid_field('name', fully_interpolated(job.name())))

  task_raw = job.task()

  MB = 1024 * 1024
  task = TaskConfig()

  def not_empty_or(item, default):
    return default if item is Empty else fully_interpolated(item)

  # job components
  task.production = fully_interpolated(job.production(), bool)
  task.isService = select_service_bit(job)
  task.maxTaskFailures = fully_interpolated(job.max_task_failures())
  task.priority = fully_interpolated(job.priority())
  task.contactEmail = not_empty_or(job.contact(), None)
  task.tier = not_empty_or(job.tier(), None)

  # Add metadata to a task, to display in the scheduler UI.
  task.metadata = frozenset(Metadata(key=str(key), value=str(value)) for key, value in metadata)

  # task components
  if not task_raw.has_resources():
    raise InvalidConfig('Task must specify resources!')

  if (fully_interpolated(task_raw.resources().ram()) == 0
      or fully_interpolated(task_raw.resources().disk()) == 0):
    raise InvalidConfig('Must specify ram and disk resources, got ram:%r disk:%r' % (
      fully_interpolated(task_raw.resources().ram()),
      fully_interpolated(task_raw.resources().disk())))

  task.numCpus = fully_interpolated(task_raw.resources().cpu())
  task.ramMb = fully_interpolated(task_raw.resources().ram()) / MB
  task.diskMb = fully_interpolated(task_raw.resources().disk()) / MB
  if task.numCpus <= 0 or task.ramMb <= 0 or task.diskMb <= 0:
    raise InvalidConfig('Task has invalid resources.  cpu/ramMb/diskMb must all be positive: '
        'cpu:%r ramMb:%r diskMb:%r' % (task.numCpus, task.ramMb, task.diskMb))

  task.job = key
  task.owner = owner
  task.requestedPorts = ports
  task.taskLinks = {}  # See AURORA-739
  task.constraints = constraints_to_thrift(not_empty_or(job.constraints(), {}))
  task.container = create_container_config(job.container())

  underlying, refs = job.interpolate()

  # need to fake an instance id for the sake of schema checking
  underlying_checked = underlying.bind(mesos={'instance': 31337, 'hostname': ''})
  try:
    ThermosTaskValidator.assert_valid_task(underlying_checked.task())
  except ThermosTaskValidator.InvalidTaskError as e:
    raise InvalidConfig('Task is invalid: %s' % e)
  if not underlying_checked.check().ok():
    raise InvalidConfig('Job not fully specified: %s' % underlying.check().message())

  unbound = []
  for ref in refs:
    if ref in (THERMOS_TASK_ID_REF, MESOS_INSTANCE_REF, MESOS_HOSTNAME_REF) or (
        Ref.subscope(THERMOS_PORT_SCOPE_REF, ref)):
      continue
    unbound.append(ref)

  if unbound:
    raise InvalidConfig('Config contains unbound variables: %s' % ' '.join(map(str, unbound)))

  task.executorConfig = ExecutorConfig(
      name=AURORA_EXECUTOR_NAME,
      data=filter_aliased_fields(underlying).json_dumps())

  return JobConfiguration(
      key=key,
      owner=owner,
      cronSchedule=not_empty_or(job.cron_schedule(), None),
      cronCollisionPolicy=select_cron_policy(job.cron_collision_policy()),
      taskConfig=task,
      instanceCount=fully_interpolated(job.instances()))
