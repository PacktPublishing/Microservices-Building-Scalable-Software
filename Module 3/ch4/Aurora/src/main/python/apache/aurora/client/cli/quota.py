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

from thrift.protocol import TJSONProtocol
from thrift.TSerialization import serialize

from apache.aurora.client.cli import EXIT_OK, Noun, Verb
from apache.aurora.client.cli.context import AuroraCommandContext
from apache.aurora.client.cli.options import JSON_WRITE_OPTION, ROLE_ARGUMENT


class GetQuotaCmd(Verb):
  @property
  def name(self):
    return 'get'

  @property
  def help(self):
    return "Print information about quotas for a role"

  def get_options(self):
    return [JSON_WRITE_OPTION, ROLE_ARGUMENT]

  def render_quota(self, write_json, quota_resp):
    def get_quota_json(quota):
      result = {}
      result['cpu'] = quota.numCpus
      result['ram'] = float(quota.ramMb) / 1024
      result['disk'] = float(quota.diskMb) / 1024
      return result

    def get_quota_str(quota):
      result = []
      result.append('  CPU: %s' % quota.numCpus)
      result.append('  RAM: %f GB' % (float(quota.ramMb) / 1024))
      result.append('  Disk: %f GB' % (float(quota.diskMb) / 1024))
      return result

    if write_json:
      return serialize(quota_resp.result.getQuotaResult,
          protocol_factory=TJSONProtocol.TSimpleJSONProtocolFactory())
    else:
      quota_result = quota_resp.result.getQuotaResult
      result = ['Allocated:']
      result += get_quota_str(quota_result.quota)
      if quota_result.prodSharedConsumption:
        result.append('Production shared pool resources consumed:')
        result += get_quota_str(quota_result.prodSharedConsumption)
      if quota_result.prodDedicatedConsumption:
        result.append('Production dedicated pool resources consumed:')
        result += get_quota_str(quota_result.prodDedicatedConsumption)
      if quota_result.nonProdSharedConsumption:
        result.append('Non-production shared pool resources consumed:')
        result += get_quota_str(quota_result.nonProdSharedConsumption)
      if quota_result.nonProdDedicatedConsumption:
        result.append('Non-production dedicated pool resources consumed:')
        result += get_quota_str(quota_result.nonProdDedicatedConsumption)
      return '\n'.join(result)

  def execute(self, context):
    (cluster, role) = context.options.role
    api = context.get_api(cluster)
    resp = api.get_quota(role)
    context.log_response_and_raise(
        resp,
        err_msg='Error retrieving quota for role %s' % role)

    context.print_out(self.render_quota(context.options.write_json, resp))
    return EXIT_OK


class Quota(Noun):
  @property
  def name(self):
    return 'quota'

  @property
  def help(self):
    return "Work with quota settings for an Apache Aurora cluster"

  @classmethod
  def create_context(cls):
    return AuroraCommandContext()

  def __init__(self):
    super(Quota, self).__init__()
    self.register_verb(GetQuotaCmd())
