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

"""Helpers for composing Thermos workflows."""

# checkstyle: noqa

import itertools

from pystachio import Empty, List
from twitter.common.lang import Compatibility

from .schema_base import GB, Constraint, Process, Resources, Task

__all__ = (
  # shorthand for process ordering constraint
  'order',

  # task combinators
  'combine_tasks',    # merge N tasks in parallel
  'concat_tasks',     # serialize N tasks

  # options helpers
  'java_options',
  'python_options',

  # the automatically-sequential version of a task
  'SequentialTask',

  # create a simple task from a command line + name
  'SimpleTask',

  # helper classes
  'Options',
  'Processes',
  'Tasks',
  'Units',
)


class Units(object):
  """Helpers for base units of Tasks and Processes."""

  @classmethod
  def safe_get(cls, unit):
    return 0 if unit is Empty else unit.get()

  @classmethod
  def optional_resources(cls, resources):
    return Resources() if resources is Empty else resources

  @classmethod
  def resources_sum(cls, *resources):
    """Add two Resources objects together."""
    def add_unit(f1, f2):
      return cls.safe_get(f1) + cls.safe_get(f2)

    def add(r1, r2):
      return Resources(cpu=add_unit(r1.cpu(), r2.cpu()),
                       ram=add_unit(r1.ram(), r2.ram()),
                       disk=add_unit(r1.disk(), r2.disk()))

    return reduce(add, map(cls.optional_resources, resources), Resources(cpu=0, ram=0, disk=0))

  @classmethod
  def finalization_wait_sum(cls, waits):
    """Return a finalization_wait that is the sum of the inputs"""
    return sum(map(cls.safe_get, waits))

  @classmethod
  def resources_max(cls, resources):
    """Return a Resource object that is the maximum of the inputs along each
      resource dimension."""
    def max_unit(f1, f2):
      return max(cls.safe_get(f1), cls.safe_get(f2))

    def resource_max(r1, r2):
      return Resources(cpu=max_unit(r1.cpu(), r2.cpu()),
                       ram=max_unit(r1.ram(), r2.ram()),
                       disk=max_unit(r1.disk(), r2.disk()))

    return reduce(resource_max,
        map(cls.optional_resources, resources), Resources(cpu=0, ram=0, disk=0))

  @classmethod
  def finalization_wait_max(cls, waits):
    """Return a finalization_wait that is the maximum of the inputs"""
    return max([0] + map(cls.safe_get, waits))

  @classmethod
  def processes_merge(cls, tasks):
    """Return a deduped list of the processes from all tasks."""
    return list(set(itertools.chain.from_iterable(task.processes() for task in tasks)))

  @classmethod
  def constraints_merge(cls, tasks):
    """Return a deduped list of the constraints from all tasks."""
    return list(set(itertools.chain.from_iterable(task.constraints() for task in tasks)))


class Processes(object):
  """Helper class for Process objects."""

  @classmethod
  def _process_name(cls, process):
    if isinstance(process, Process):
      return process.name()
    elif isinstance(process, Compatibility.string):
      return process
    raise ValueError("Unknown value for process order: %s" % repr(process))

  @classmethod
  def order(cls, *processes):
    """Given a list of processes, return the list of constraints that keeps them in order, e.g.
       order(p1, p2, p3) => [Constraint(order=[p1.name(), p2.name(), p3.name()])].

       Similarly, concatenation operations are valid, i.e.
          order(p1, p2) + order(p2, p3) <=> order(p1, p2, p3)
    """
    return [Constraint(order=[cls._process_name(p) for p in processes])]


class Tasks(object):
  """Helper class for Task objects."""

  SIMPLE_CPU  = 1.0
  SIMPLE_RAM  = 1 * GB
  SIMPLE_DISK = 1 * GB

  @classmethod
  def _combine_processes(cls, *tasks):
    """Given multiple tasks, merge their processes together, retaining the identity of the first
       task."""
    if len(tasks) == 0:
      return Task()
    head_task = tasks[-1]
    return head_task(processes=Units.processes_merge(tasks))

  @classmethod
  def combine(cls, *tasks, **kw):
    """Given multiple tasks, return a Task that runs all processes in parallel."""
    if len(tasks) == 0:
      return Task()
    base = cls._combine_processes(*tasks)
    return base(
      resources=Units.resources_sum(*(task.resources() for task in tasks)),
      constraints=Units.constraints_merge(tasks),
      finalization_wait=Units.finalization_wait_max(task.finalization_wait() for task in tasks),
      **kw
    )

  @classmethod
  def concat(cls, *tasks, **kw):
    """Given tasks T1...TN, return a single Task that runs all processes such that
       all processes in Tk run before any process in Tk+1."""
    if len(tasks) == 0:
      return Task()
    base = cls._combine_processes(*tasks)
    base_constraints = Units.constraints_merge(tasks)
    # TODO(wickman) be smarter about this in light of existing constraints
    for (t1, t2) in zip(tasks[0:-1], tasks[1:]):
      for p1 in t1.processes():
        for p2 in t2.processes():
          if p1 != p2:
            base_constraints.extend(Processes.order(p1, p2))
    return base(
        resources=Units.resources_max(task.resources() for task in tasks),
        constraints=base_constraints,
        finalization_wait=Units.finalization_wait_sum(task.finalization_wait() for task in tasks),
        **kw)

  @classmethod
  def simple(cls, name, command):
    """Create a usable Task from a provided name + command line and a default set of resources"""
    return Task(
      name=name,
      processes=[Process(name=name, cmdline=command)],
      resources=Resources(cpu=cls.SIMPLE_CPU,
                          ram=cls.SIMPLE_RAM,
                          disk=cls.SIMPLE_DISK))

  @classmethod
  def sequential(cls, task):
    """Add a constraint that makes all processes within a task run sequentially."""
    def maybe_constrain(task):
      return {'constraints': order(*task.processes())} if task.processes() is not Empty else {}
    if task.constraints() is Empty or task.constraints() == List(Constraint)([]):
      return task(**maybe_constrain(task))
    raise ValueError('Cannot turn a Task with existing constraints into a SequentialTask!')


class Options(object):
  """Helper class for constructing command-line arguments."""

  @classmethod
  def render_option(cls, short_prefix, long_prefix, option, value=None):
    option = '%s%s' % (short_prefix if len(option) == 1 else long_prefix, option)
    return '%s %s' % (option, value) if value else option

  @classmethod
  def render_options(cls, short_prefix, long_prefix, *options, **kw_options):
    renders = []

    for option in options:
      if isinstance(option, Compatibility.string):
        renders.append(cls.render_option(short_prefix, long_prefix, option))
      elif isinstance(option, dict):
        # preserve order in case option is an OrderedDict, rather than recursing with **option
        for argument, value in option.items():
          renders.append(cls.render_option(short_prefix, long_prefix, argument, value))
      else:
        raise ValueError('Got an unexpected argument to render_options: %s' % repr(option))

    for argument, value in kw_options.items():
      renders.append(cls.render_option(short_prefix, long_prefix, argument, value))

    return renders

  @classmethod
  def java(cls, *options, **kw_options):
    """
      Given a set of arguments, keyword arguments or dictionaries, render
      command-line parameters accordingly.  For example:

        java_options('a', 'b') == '-a -b'
        java_options({
          'a': 23,
          'b': 'foo'
        }) == '-a 23 -b foo'
        java_options(a=23, b='foo') == '-a 23 -b foo'
    """
    return ' '.join(cls.render_options('-', '-', *options, **kw_options))

  @classmethod
  def python(cls, *options, **kw_options):
    """
      Given a set of arguments, keyword arguments or dictionaries, render
      command-line parameters accordingly.  Single letter parameters are
      rendered with single '-'.  For example:

        python_options('a', 'boo') == '-a --boo'
        python_options({
          'a': 23,
          'boo': 'foo'
        }) == '-a 23 --boo foo'
        python_options(a=23, boo='foo') == '-a 23 --boo foo'
    """
    return ' '.join(cls.render_options('-', '--', *options, **kw_options))


def SimpleTask(name, command):
  """A simple command-line Task with default resources"""
  return Tasks.simple(name, command)


def SequentialTask(*args, **kw):
  """A Task whose processes are always sequential."""
  return Tasks.sequential(Task(*args, **kw))


python_options = Options.python
java_options = Options.java
combine_tasks = Tasks.combine
concat_tasks = Tasks.concat
order = Processes.order
