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

import os
import posixpath
import sys
import time

from kazoo.client import KazooClient
from kazoo.exceptions import NoNodeError

OK = 1
DID_NOT_REGISTER = 2
DID_NOT_RECOVER_FROM_EXPIRY = 3


serverset = os.getenv('SERVERSET')
client = KazooClient('localhost:2181')
client.start()


def wait_until_znodes(count, timeout=30):
  now = time.time()
  timeout += now
  while now < timeout:
    try:
      children = client.get_children(serverset)
    except NoNodeError:
      children = []
    print('Announced members: %s' % children)
    if len(children) == count:
      return [posixpath.join(serverset, child) for child in children]
    time.sleep(1)
    now += 1
  return []


# job is created with 3 znodes.
znodes = wait_until_znodes(3, timeout=10)
if not znodes:
  sys.exit(DID_NOT_REGISTER)

client.delete(znodes[0])

znodes = wait_until_znodes(3, timeout=10)
if not znodes:
  sys.exit(DID_NOT_RECOVER_FROM_EXPIRY)

sys.exit(OK)
