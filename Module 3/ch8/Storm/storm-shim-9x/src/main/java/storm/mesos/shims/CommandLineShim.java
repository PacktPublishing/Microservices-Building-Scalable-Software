/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package storm.mesos.shims;

public class CommandLineShim implements ICommandLineShim {
  String extraConfig;

  public CommandLineShim(String extraConfig) {
    this.extraConfig = extraConfig;
  }

  public String getCommandLine(String topologyId) {
    return String.format(
        "export STORM_SUPERVISOR_LOG_FILE=%s-supervisor.log" +
        " && cp storm.yaml storm-mesos*/conf" +
        " && cd storm-mesos*" +
        " && python bin/storm supervisor storm.mesos.MesosSupervisor%s",
        topologyId, extraConfig);
  }

}
