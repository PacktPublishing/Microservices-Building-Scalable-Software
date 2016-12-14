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

from twitter.common.lang import Interface


class AuthModule(Interface):
  @abstractproperty
  def mechanism(self):
    """Return the mechanism provided by this AuthModule.
    ":rtype: string
    """

  @abstractmethod
  def auth(self):
    """Authentication handler for the HTTP transport layer.
    :rtype: requests.auth.AuthBase.
    """

  @abstractproperty
  def failed_auth_message(self):
    """Default help message to log on failed auth attempt.
    :rtype: string
    """


class InsecureAuthModule(AuthModule):
  @property
  def mechanism(self):
    return 'UNAUTHENTICATED'

  def auth(self):
    return None

  @property
  def failed_auth_message(self):
    return ''
