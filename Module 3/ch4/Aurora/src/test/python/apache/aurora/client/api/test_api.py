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

from mock import create_autospec

from apache.aurora.client.api import AuroraClientAPI
from apache.aurora.common.aurora_job_key import AuroraJobKey
from apache.aurora.common.cluster import Cluster
from apache.aurora.config import AuroraConfig
from apache.aurora.config.schema.base import UpdateConfig

from ...api_util import SchedulerThriftApiSpec

from gen.apache.aurora.api.ttypes import (
    InstanceKey,
    JobConfiguration,
    JobKey,
    JobUpdateKey,
    JobUpdateQuery,
    JobUpdateRequest,
    JobUpdateSettings,
    JobUpdateStatus,
    Response,
    ResponseCode,
    ResponseDetail,
    Result,
    TaskConfig
)


class TestJobUpdateApis(unittest.TestCase):
  """Job update APIs tests."""

  UPDATE_CONFIG = {
      'batch_size': 1,
      'watch_secs': 50,
      'max_per_shard_failures': 2,
      'max_total_failures': 1,
      'rollback_on_failure': True,
      'wait_for_batch_completion': False,
  }

  JOB_KEY = AuroraJobKey("foo", "role", "env", "name")
  UPDATE_KEY = JobUpdateKey(job=JOB_KEY.to_thrift(), id="id")

  @classmethod
  def create_blank_response(cls, code, msg):
    return Response(
      responseCode=code,
      details=[ResponseDetail(message=msg)],
      result=create_autospec(spec=Result, spec_set=True, instance=True)
    )

  @classmethod
  def create_simple_success_response(cls):
    return cls.create_blank_response(ResponseCode.OK, 'OK')

  @classmethod
  def create_error_response(cls):
    return cls.create_blank_response(ResponseCode.ERROR, 'ERROR')

  @classmethod
  def mock_api(cls):
    api = AuroraClientAPI(Cluster(name="foo"), 'test-client')
    mock_proxy = create_autospec(spec=SchedulerThriftApiSpec, spec_set=True, instance=True)
    api._scheduler_proxy = mock_proxy
    return api, mock_proxy

  @classmethod
  def create_update_settings(cls):
    return JobUpdateSettings(
        updateGroupSize=1,
        maxPerInstanceFailures=2,
        maxFailedInstances=1,
        minWaitInInstanceRunningMs=50 * 1000,
        rollbackOnFailure=True,
        waitForBatchCompletion=False)

  @classmethod
  def create_update_request(cls, task_config):
    return JobUpdateRequest(
        instanceCount=5,
        settings=cls.create_update_settings(),
        taskConfig=task_config)

  @classmethod
  def mock_job_config(cls, error=None):
    config = create_autospec(spec=AuroraConfig, instance=True)
    mock_get = create_autospec(spec=UpdateConfig, instance=True)
    mock_get.get.return_value = cls.UPDATE_CONFIG
    if error:
      config.update_config.side_effect = error
    else:
      config.update_config.return_value = mock_get
    mock_task_config = create_autospec(spec=JobConfiguration, instance=True)
    mock_task_config.taskConfig = TaskConfig()
    config.job.return_value = mock_task_config
    config.instances.return_value = 5
    return config

  def test_add_instances(self):
    """Test adding instances."""
    api, mock_proxy = self.mock_api()
    job_key = AuroraJobKey("foo", "role", "env", "name")
    mock_proxy.addInstances.return_value = self.create_simple_success_response()
    api.add_instances(job_key, 1, 10)

    mock_proxy.addInstances.assert_called_once_with(
        InstanceKey(jobKey=job_key.to_thrift(), instanceId=1),
        10)

  def test_start_job_update(self):
    """Test successful job update start."""
    api, mock_proxy = self.mock_api()
    task_config = TaskConfig()
    mock_proxy.startJobUpdate.return_value = self.create_simple_success_response()

    api.start_job_update(self.mock_job_config(), instances=None, message='hello')
    mock_proxy.startJobUpdate.assert_called_once_with(
        self.create_update_request(task_config),
        'hello')

  def test_start_job_update_fails_parse_update_config(self):
    """Test start_job_update fails to parse invalid UpdateConfig."""
    api, mock_proxy = self.mock_api()

    self.assertRaises(
        AuroraClientAPI.UpdateConfigError,
        api.start_job_update,
        self.mock_job_config(error=ValueError()),
        None)

  def test_get_job_update_diff(self):
    """Test getting job update diff."""
    api, mock_proxy = self.mock_api()
    task_config = TaskConfig()
    mock_proxy.getJobUpdateDiff.return_value = self.create_simple_success_response()

    api.get_job_update_diff(self.mock_job_config(), instances=None)
    mock_proxy.getJobUpdateDiff.assert_called_once_with(self.create_update_request(task_config))

  def test_pause_job_update(self):
    """Test successful job update pause."""
    api, mock_proxy = self.mock_api()
    mock_proxy.pauseJobUpdate.return_value = self.create_simple_success_response()

    api.pause_job_update(self.UPDATE_KEY, message='hello')
    mock_proxy.pauseJobUpdate.assert_called_once_with(self.UPDATE_KEY, 'hello')

  def test_resume_job_update(self):
    """Test successful job update resume."""
    api, mock_proxy = self.mock_api()
    mock_proxy.resumeJobUpdate.return_value = self.create_simple_success_response()

    api.resume_job_update(self.UPDATE_KEY, message='hello')
    mock_proxy.resumeJobUpdate.assert_called_once_with(self.UPDATE_KEY, 'hello')

  def test_query_job_updates(self):
    """Test querying job updates."""
    api, mock_proxy = self.mock_api()
    job_key = AuroraJobKey("foo", "role", "env", "name")
    query = JobUpdateQuery(
        jobKey=job_key.to_thrift(),
        updateStatuses={JobUpdateStatus.ROLLING_FORWARD})
    api.query_job_updates(job_key=job_key, update_statuses=query.updateStatuses)
    mock_proxy.getJobUpdateSummaries.assert_called_once_with(query)

  def test_query_job_updates_no_filter(self):
    """Test querying job updates with no filter args."""
    api, mock_proxy = self.mock_api()
    query = JobUpdateQuery()
    api.query_job_updates()
    mock_proxy.getJobUpdateSummaries.assert_called_once_with(query)

  def test_get_job_update_details(self):
    """Test getting job update details."""
    api, mock_proxy = self.mock_api()
    key = JobUpdateKey(job=JobKey(role="role", environment="env", name="name"), id="id")
    api.get_job_update_details(key)
    mock_proxy.getJobUpdateDetails.assert_called_once_with(key)
