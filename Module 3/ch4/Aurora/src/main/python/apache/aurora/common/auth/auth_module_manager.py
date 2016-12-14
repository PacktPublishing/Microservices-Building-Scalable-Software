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
from twitter.common import log

from .auth_module import AuthModule, InsecureAuthModule

_INSECURE_AUTH_MODULE = InsecureAuthModule()
_AUTH_MODULES = {
  _INSECURE_AUTH_MODULE.mechanism: _INSECURE_AUTH_MODULE,
}
DEFAULT_AUTH_MECHANISM = 'UNAUTHENTICATED'


class SessionKeyError(Exception): pass


def register_auth_module(auth_module):
  """
    Add an auth module into the registry used by make_session_key. An auth module is discovered
    via its auth mechanism.

    args:
      auth_module: A 0-arg callable that should return a SessionKey or raises a SessionKeyError
                   and extend AuthModule.
  """
  if not isinstance(auth_module, AuthModule):
    raise TypeError('Given auth module must be a AuthModule subclass, got %s' % type(auth_module))
  _AUTH_MODULES[auth_module.mechanism] = auth_module


def get_auth_handler(auth_mechanism=DEFAULT_AUTH_MECHANISM):
  """Returns an auth handler to be used in Thrift transport layer."""
  if not _AUTH_MODULES:
    raise SessionKeyError('No auth modules have been registered. Please call register_auth_module.')

  auth_module = _AUTH_MODULES.get(auth_mechanism) or _INSECURE_AUTH_MODULE
  log.debug('Using auth module: %r' % auth_module)
  return auth_module
