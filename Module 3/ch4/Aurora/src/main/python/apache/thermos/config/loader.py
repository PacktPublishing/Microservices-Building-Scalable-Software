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
import os
import random
import re
import textwrap

from pystachio import Ref
from pystachio.config import Config
from twitter.common.dirutil import safe_open

from apache.thermos.common.planner import TaskPlanner
from apache.thermos.config.schema import Task, ThermosContext


class PortExtractor(object):
  class InvalidPorts(Exception): pass

  @classmethod
  def extract(cls, obj):
    port_scope = Ref.from_address('thermos.ports')
    _, uninterp = obj.interpolate()
    ports = []
    for ref in uninterp:
      subscope = port_scope.scoped_to(ref)
      if subscope is not None:
        if not subscope.is_index():
          raise cls.InvalidPorts(
            'Bad port specification "%s" (should be of form "thermos.ports[name]"' % ref.address())
        ports.append(subscope.action().value)
    return ports


class ThermosProcessWrapper(object):
  # >=1 characters && anything but NULL and '/'
  VALID_PROCESS_NAME_RE = re.compile(r'^[^./][^/]*$')
  class InvalidProcess(Exception): pass

  def __init__(self, process):
    self._process = process

  def ports(self):
    try:
      return PortExtractor.extract(self._process)
    except PortExtractor.InvalidPorts:
      raise self.InvalidProcess('Process has invalid ports scoping!')

  @classmethod
  def assert_valid_process_name(cls, name):
    if not cls.VALID_PROCESS_NAME_RE.match(name):
      raise cls.InvalidProcess('Invalid process name: %s' % name)


class ThermosTaskWrapper(object):
  class InvalidTask(Exception): pass

  def __init__(self, task, bindings=None, strict=True):
    if bindings:
      task = task.bind(*bindings)
    if not task.check().ok() and strict:
      raise self.InvalidTask(task.check().message())
    self._task = task

  @property
  def task(self):
    return self._task

  def ports(self):
    ti, _ = self._task.interpolate()
    ports = set()
    if ti.has_processes():
      for process in ti.processes():
        try:
          ports.update(ThermosProcessWrapper(process).ports())
        except ThermosProcessWrapper.InvalidProcess:
          raise self.InvalidTask('Task has invalid process: %s' % process)
    return ports

  def to_json(self):
    return json.dumps(self._task.get())

  def to_file(self, filename):
    ti, _ = self._task.interpolate()
    with safe_open(filename, 'w') as fp:
      json.dump(ti.get(), fp)

  @classmethod
  def from_file(cls, filename, **kw):
    try:
      with safe_open(filename) as fp:
        task = Task.json_load(fp)
      return cls(task, **kw)
    except Exception:
      return None


# TODO(wickman) These should be validators pushed onto ThermosConfigLoader.plugins
class ThermosTaskValidator(object):
  class InvalidTaskError(Exception): pass

  @classmethod
  def assert_valid_task(cls, task):
    cls.assert_valid_names(task)
    cls.assert_typecheck(task)
    cls.assert_valid_plan(task)
    cls.assert_all_refs_bound(task)

  @classmethod
  def assert_valid_plan(cls, task):
    try:
      TaskPlanner(task, process_filter=lambda proc: proc.final().get() is False)
      TaskPlanner(task, process_filter=lambda proc: proc.final().get() is True)
    except TaskPlanner.InvalidSchedule as e:
      raise cls.InvalidTaskError('Task has invalid plan: %s' % e)

  @classmethod
  def assert_valid_names(cls, task):
    for process in task.processes():
      name = process.name().get()
      try:
        ThermosProcessWrapper.assert_valid_process_name(name)
      except ThermosProcessWrapper.InvalidProcess as e:
        raise cls.InvalidTaskError('Task has invalid process: %s' % e)

  @classmethod
  def assert_typecheck(cls, task):
    typecheck = task.check()
    if not typecheck.ok():
      raise cls.InvalidTaskError('Failed to fully evaluate task: %s' %
        typecheck.message())

  @classmethod
  def assert_valid_ports(cls, task, portmap):
    for port in ThermosTaskWrapper(task).ports():
      if port not in portmap:
        raise cls.InvalidTaskError('Task requires unbound port %s!' % port)

  @classmethod
  def assert_same_task(cls, spec, task):
    active_task = spec.given(state='active').getpath('task_path')
    if os.path.exists(active_task):
      task_on_disk = ThermosTaskWrapper.from_file(active_task)
      if not task_on_disk or task_on_disk.task != task:
        raise cls.InvalidTaskError('Task differs from on disk copy: %r vs %r' % (
            task_on_disk.task if task_on_disk else None, task))

  @classmethod
  def assert_all_refs_bound(cls, task):
    port_names = PortExtractor.extract(task)

    # Create fake bindings and make sure that there are no unbound refs afterwards.  If
    # there are unbound refs that could indicate improper scoping e.g.
    # {{array[{{mesos.instance}}]}} which is disallowed.
    thermos_bindings = ThermosContext(
        task_id='dummy_task_id',
        user='dummy_user',
        ports=dict((name, random.randrange(30000, 40000)) for name in port_names),
    )
    task_instance, unbindable_refs = (task % dict(thermos=thermos_bindings)).interpolate()

    if len(unbindable_refs) != 0:
      raise cls.InvalidTaskError(
          'Unexpected unbound refs: %s. Make sure you are not nesting template variables.'
          % ' '.join(map(str, unbindable_refs)))


class ThermosConfigLoader(object):
  SCHEMA = textwrap.dedent("""
    from pystachio import *
    from apache.thermos.config.schema import *

    __TASKS = []

    def export(task):
      __TASKS.append(Task(task) if isinstance(task, dict) else task)
  """)

  @classmethod
  def load(cls, loadable, **kw):
    config = Config(loadable, schema=cls.SCHEMA)
    return cls(ThermosTaskWrapper(task, **kw) for task in config.environment['__TASKS'])

  @classmethod
  def load_json(cls, filename, **kw):
    tc = cls()
    task = ThermosTaskWrapper.from_file(filename, **kw)
    if task:
      ThermosTaskValidator.assert_valid_task(task.task())
      tc.add_task(task)
    return tc

  def __init__(self, exported_tasks=None):
    self._exported_tasks = exported_tasks or []

  def add_task(self, task):
    self._exported_tasks.append(task)

  def tasks(self):
    return self._exported_tasks
