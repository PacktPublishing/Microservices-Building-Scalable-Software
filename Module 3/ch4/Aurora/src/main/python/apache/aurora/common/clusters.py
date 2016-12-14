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

from __future__ import print_function

import json
import os
from collections import Mapping
from contextlib import contextmanager

from pystachio import Required, String
from twitter.common.collections import maybe_list

from .cluster import Cluster

__all__ = (
  'CLUSTERS',
  'Clusters',
)


class NameTrait(Cluster.Trait):
  name = Required(String)  # noqa


class Clusters(Mapping):
  class Error(Exception): pass
  class ClusterExists(Error): pass
  class ClusterNotFound(KeyError, Error): pass
  class UnknownFormatError(Error): pass
  class ParseError(Error): pass

  @classmethod
  def from_file(cls, filename):
    return cls(list(cls.iter_clusters(filename)))

  @classmethod
  def iter_clusters(cls, filename):
    with open(filename) as fp:
      try:
        document = json.load(fp)
      except ValueError as e:
        raise cls.ParseError('Unable to parse %s: %s' % (filename, e))
      if isinstance(document, list):
        iterator = document
      elif isinstance(document, dict):
        iterator = document.values()
      else:
        raise cls.ParseError('Unknown layout in %s' % filename)
      for document in iterator:
        if not isinstance(document, dict):
          raise cls.ParseError('Clusters must be maps of key/value pairs, got %s' % type(document))
        # documents not adhering to NameTrait are ignored.
        if 'name' not in document:
          continue
        yield Cluster(**document)

  def __init__(self, cluster_list):
    self.replace(cluster_list)

  def replace(self, cluster_list):
    self._clusters = {}
    self.update(cluster_list)

  def update(self, cluster_list):
    cluster_list = maybe_list(cluster_list, expected_type=Cluster, raise_type=TypeError)
    for cluster in cluster_list:
      self.add(cluster)

  def add(self, cluster):
    """Add a cluster to this Clusters map."""
    cluster = Cluster(**cluster)
    cluster.check_trait(NameTrait)
    self._clusters[cluster.name] = cluster

  @contextmanager
  def patch(self, cluster_list):
    """Patch this Clusters instance with a new list of clusters in a
       contextmanager.  Intended for testing purposes."""
    old_clusters = self._clusters.copy()
    try:
      self.replace(cluster_list)
      yield self
    finally:
      self._clusters = old_clusters

  def __iter__(self):
    return iter(self._clusters)

  def __len__(self):
    return len(self._clusters)

  def __getitem__(self, name):
    try:
      return self._clusters[name]
    except KeyError:
      raise self.ClusterNotFound('Unknown cluster %s, valid clusters: %s' % (
          name, ', '.join(self._clusters.keys())))


DEFAULT_SEARCH_PATHS = (
  os.environ.get('AURORA_CONFIG_ROOT') or '/etc/aurora',
  os.path.expanduser('~/.aurora')
)


CLUSTERS = Clusters(())


def load():
  """(re-)load all clusters from the search path."""
  for search_path in DEFAULT_SEARCH_PATHS:
    filename = os.path.join(search_path, 'clusters.json')
    if os.path.exists(filename):
      CLUSTERS.update(Clusters.from_file(filename).values())


load()
