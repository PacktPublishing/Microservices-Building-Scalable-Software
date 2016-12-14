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

from abc import abstractmethod, abstractproperty

from mesos.interface.mesos_pb2 import TaskState
from twitter.common import log
from twitter.common.lang import Interface
from twitter.common.metrics import Observable


class StatusResult(object):
  """
    Encapsulates a reason for failure and a status value from mesos.interface.mesos_pb2.TaskStatus.
    As mesos 0.20.0 uses protobuf 2.5.0, see the EnumTypeWrapper[1] docs for more information.

    https://code.google.com/p/protobuf/source/browse/tags/2.5.0/
        python/google/protobuf/internal/enum_type_wrapper.py
  """

  def __init__(self, reason, status):
    self._reason = reason
    if status not in TaskState.values():
      raise ValueError('Unknown task state: %r' % status)
    self._status = status

  @property
  def reason(self):
    return self._reason

  @property
  def status(self):
    return self._status

  def __repr__(self):
    return '%s(%r, status=%r)' % (
        self.__class__.__name__,
        self._reason,
        TaskState.Name(self._status))


class StatusChecker(Observable, Interface):
  """Interface to pluggable status checkers for the Aurora Executor."""

  @abstractproperty
  def status(self):
    """Return None under normal operations.  Return StatusResult to indicate status proposal."""

  def name(self):
    """Return the name of the status checker.  By default it is the class name.  Subclassable."""
    return self.__class__.__name__

  def start(self):
    """Invoked once the task has been started."""
    pass

  def stop(self):
    """Invoked once a non-None status has been reported."""
    pass


class StatusCheckerProvider(Interface):
  @abstractmethod
  def from_assigned_task(self, assigned_task, sandbox):
    pass


class Healthy(StatusChecker):
  @property
  def status(self):
    return None


class ChainedStatusChecker(StatusChecker):
  def __init__(self, status_checkers):
    self._status_checkers = status_checkers
    self._status = None
    if not all(isinstance(h_i, StatusChecker) for h_i in status_checkers):
      raise TypeError('ChainedStatusChecker must take an iterable of StatusCheckers.')
    super(ChainedStatusChecker, self).__init__()

  @property
  def status(self):
    if self._status is None:
      for status_checker in self._status_checkers:
        status_checker_status = status_checker.status
        if status_checker_status is not None:
          log.info('%s reported %s' % (status_checker.__class__.__name__, status_checker_status))
          if not isinstance(status_checker_status, StatusResult):
            raise TypeError('StatusChecker returned something other than a StatusResult: got %s' %
                type(status_checker_status))
          self._status = status_checker_status
          break
    return self._status

  def start(self):
    for status_checker in self._status_checkers:
      status_checker.start()

  def stop(self):
    for status_checker in self._status_checkers:
      status_checker.stop()
