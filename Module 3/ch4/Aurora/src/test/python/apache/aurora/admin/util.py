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

import unittest
from collections import defaultdict

from mock import create_autospec

from apache.aurora.client.api.sla import DomainUpTimeSlaVector, JobUpTimeDetails
from apache.aurora.client.hooks.hooked_api import HookedAuroraClientAPI
from apache.aurora.common.aurora_job_key import AuroraJobKey
from apache.aurora.common.cluster import Cluster
from apache.aurora.common.clusters import Clusters

from ..api_util import SchedulerProxyApiSpec

from gen.apache.aurora.api.ttypes import Response, ResponseCode, ResponseDetail, Result


class AuroraClientCommandTest(unittest.TestCase):
  @classmethod
  def create_blank_response(cls, code, msg):
    # TODO(wfarner): Don't use a mock here.
    response = create_autospec(spec=Response, instance=True)
    response.responseCode = code
    response.result = create_autospec(spec=Result, instance=True)
    response.details = [ResponseDetail(message=msg)]
    return response

  @classmethod
  def create_simple_success_response(cls):
    return cls.create_blank_response(ResponseCode.OK, 'OK')

  @classmethod
  def create_mock_api(cls):
    """Builds up a mock API object, with a mock SchedulerProxy"""
    mock_scheduler_proxy = create_autospec(
        spec=SchedulerProxyApiSpec,
        spec_set=False,
        instance=True)
    mock_scheduler_proxy.url = "http://something_or_other"
    mock_scheduler_proxy.scheduler_client.return_value = mock_scheduler_proxy
    mock_api = create_autospec(spec=HookedAuroraClientAPI)
    mock_api.scheduler_proxy = mock_scheduler_proxy
    return mock_api, mock_scheduler_proxy

  TEST_CLUSTER = 'west'

  TEST_CLUSTERS = Clusters([Cluster(
      name='west',
      zk='zookeeper.example.com',
      scheduler_zk_path='/foo/bar',
      auth_mechanism='UNAUTHENTICATED')])

  @classmethod
  def create_mock_probe_hosts_vector(cls, side_effects):
    mock_vector = create_autospec(spec=DomainUpTimeSlaVector, instance=True)
    mock_vector.probe_hosts.side_effect = side_effects
    return mock_vector

  @classmethod
  def create_probe_hosts(cls, hostname, predicted, safe, safe_in):
    hosts = defaultdict(list)
    job = AuroraJobKey.from_path('west/role/env/job-%s' % hostname)
    hosts[hostname].append(JobUpTimeDetails(job, predicted, safe, safe_in))
    return [hosts]

  # TODO(wfarner): Remove this, force tests to call out their flags.
  @classmethod
  def setup_mock_options(cls):
    mock_options = create_autospec(spec=['verbosity'], instance=True)
    mock_options.verbosity = 'verbose'
    return mock_options
