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

from apache.aurora.common.aurora_job_key import AuroraJobKey


# TODO(ksweeney): Moar coverage
class AuroraJobKeyTest(unittest.TestCase):
  def test_basic(self):
    AuroraJobKey.from_path("smf1/mesos/test/labrat")

  def test_equality(self):
    key1 = AuroraJobKey('cluster', 'role', 'env', 'name')
    key2 = AuroraJobKey('cluster', 'role', 'env', 'name')

    assert key1 == key2
    assert not (key1 != key2)

  def test_inequality(self):
    base = AuroraJobKey('cluster', 'role', 'env', 'name')
    keys = [AuroraJobKey('XXXXXXX', 'role', 'env', 'name'),
            AuroraJobKey('cluster', 'XXXX', 'env', 'name'),
            AuroraJobKey('cluster', 'role', 'XXX', 'name'),
            AuroraJobKey('cluster', 'role', 'env', 'XXXX')]

    for key in keys:
      assert base != key
      assert not (base == key)
