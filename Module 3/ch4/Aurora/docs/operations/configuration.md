# Scheduler Configuration

The Aurora scheduler can take a variety of configuration options through command-line arguments.
Examples are available under `examples/scheduler/`. For a list of available Aurora flags and their
documentation, see [Scheduler Configuration Reference](../reference/scheduler-configuration.md).


## A Note on Configuration
Like Mesos, Aurora uses command-line flags for runtime configuration. As such the Aurora
"configuration file" is typically a `scheduler.sh` shell script of the form.

    #!/bin/bash
    AURORA_HOME=/usr/local/aurora-scheduler

    # Flags controlling the JVM.
    JAVA_OPTS=(
      -Xmx2g
      -Xms2g
      # GC tuning, etc.
    )

    # Flags controlling the scheduler.
    AURORA_FLAGS=(
      # Port for client RPCs and the web UI
      -http_port=8081
      # Log configuration, etc.
    )

    # Environment variables controlling libmesos
    export JAVA_HOME=...
    export GLOG_v=1
    # Port used to communicate with the Mesos master and for the replicated log
    export LIBPROCESS_PORT=8083

    JAVA_OPTS="${JAVA_OPTS[*]}" exec "$AURORA_HOME/bin/aurora-scheduler" "${AURORA_FLAGS[@]}"

That way Aurora's current flags are visible in `ps` and in the `/vars` admin endpoint.


## Replicated Log Configuration

Aurora schedulers use ZooKeeper to discover log replicas and elect a leader. Only one scheduler is
leader at a given time - the other schedulers follow log writes and prepare to take over as leader
but do not communicate with the Mesos master. Either 3 or 5 schedulers are recommended in a
production deployment depending on failure tolerance and they must have persistent storage.

Below is a summary of scheduler storage configuration flags that either don't have default values
or require attention before deploying in a production environment.

### `-native_log_quorum_size`
Defines the Mesos replicated log quorum size. In a cluster with `N` schedulers, the flag
`-native_log_quorum_size` should be set to `floor(N/2) + 1`. So in a cluster with 1 scheduler
it should be set to `1`, in a cluster with 3 it should be set to `2`, and in a cluster of 5 it
should be set to `3`.

  Number of schedulers (N) | ```-native_log_quorum_size``` setting (```floor(N/2) + 1```)
  ------------------------ | -------------------------------------------------------------
  1                        | 1
  3                        | 2
  5                        | 3
  7                        | 4

*Incorrectly setting this flag will cause data corruption to occur!*

### `-native_log_file_path`
Location of the Mesos replicated log files. Consider allocating a dedicated disk (preferably SSD)
for Mesos replicated log files to ensure optimal storage performance.

### `-native_log_zk_group_path`
ZooKeeper path used for Mesos replicated log quorum discovery.

See [code](../../src/main/java/org/apache/aurora/scheduler/log/mesos/MesosLogStreamModule.java) for
other available Mesos replicated log configuration options and default values.

### Changing the Quorum Size
Special care needs to be taken when changing the size of the Aurora scheduler quorum.
Since Aurora uses a Mesos replicated log, similar steps need to be followed as when
[changing the mesos quorum size](http://mesos.apache.org/documentation/latest/operational-guide).

As a preparation, increase `-native_log_quorum_size` on each existing scheduler and restart them.
When updating from 3 to 5 schedulers, the quorum size would grow from 2 to 3.

When starting the new schedulers, use the `-native_log_quorum_size` set to the new value. Failing to
first increase the quorum size on running schedulers can in some cases result in corruption
or truncating of the replicated log used by Aurora. In that case, see the documentation on
[recovering from backup](backup-restore.md).


## Backup Configuration

Configuration options for the Aurora scheduler backup manager.

### `-backup_interval`
The interval on which the scheduler writes local storage backups.  The default is every hour.

### `-backup_dir`
Directory to write backups to.

### `-max_saved_backups`
Maximum number of backups to retain before deleting the oldest backup(s).


## Process Logs

### Log destination
By default, Thermos will write process stdout/stderr to log files in the sandbox. Process object configuration
allows specifying alternate log file destinations like streamed stdout/stderr or suppression of all log output.
Default behavior can be configured for the entire cluster with the following flag (through the `-thermos_executor_flags`
argument to the Aurora scheduler):

    --runner-logger-destination=both

`both` configuration will send logs to files and stream to parent stdout/stderr outputs.

See [Configuration Reference](../reference/configuration.md#logger) for all destination options.

### Log rotation
By default, Thermos will not rotate the stdout/stderr logs from child processes and they will grow
without bound. An individual user may change this behavior via configuration on the Process object,
but it may also be desirable to change the default configuration for the entire cluster.
In order to enable rotation by default, the following flags can be applied to Thermos (through the
-thermos_executor_flags argument to the Aurora scheduler):

    --runner-logger-mode=rotate
    --runner-rotate-log-size-mb=100
    --runner-rotate-log-backups=10

In the above example, each instance of the Thermos runner will rotate stderr/stdout logs once they
reach 100 MiB in size and keep a maximum of 10 backups. If a user has provided a custom setting for
their process, it will override these default settings.



## Thermos Executor Wrapper

If you need to do computation before starting the thermos executor (for example, setting a different
`--announcer-hostname` parameter for every executor), then the thermos executor should be invoked
 inside a wrapper script. In such a case, the aurora scheduler should be started with
 `-thermos_executor_path` pointing to the wrapper script and `-thermos_executor_resources`
 set to a comma separated string of all the resources that should be copied into
 the sandbox (including the original thermos executor).

For example, to wrap the executor inside a simple wrapper, the scheduler will be started like this
`-thermos_executor_path=/path/to/wrapper.sh -thermos_executor_resources=/usr/share/aurora/bin/thermos_executor.pex`



### Docker containers
In order for Aurora to launch jobs using docker containers, a few extra configuration options
must be set.  The [docker containerizer](http://mesos.apache.org/documentation/latest/docker-containerizer/)
must be enabled on the mesos slaves by launching them with the `--containerizers=docker,mesos` option.

By default, Aurora will configure Mesos to copy the file specified in `-thermos_executor_path`
into the container's sandbox.  If using a wrapper script to launch the thermos executor,
specify the path to the wrapper in that argument. In addition, the path to the executor pex itself
must be included in the `-thermos_executor_resources` option. Doing so will ensure that both the
wrapper script and executor are correctly copied into the sandbox. Finally, ensure the wrapper
script does not access resources outside of the sandbox, as when the script is run from within a
docker container those resources will not exist.

A scheduler flag, `-global_container_mounts` allows mounting paths from the host (i.e., the slave)
into all containers on that host. The format is a comma separated list of host_path:container_path[:mode]
tuples. For example `-global_container_mounts=/opt/secret_keys_dir:/mnt/secret_keys_dir:ro` mounts
`/opt/secret_keys_dir` from the slaves into all launched containers. Valid modes are `ro` and `rw`.

If you would like to run a container with a read-only filesystem, it may also be necessary to
pass to use the scheduler flag `-thermos_home_in_sandbox` in order to set HOME to the sandbox
before the executor runs. This will make sure that the executor/runner PEX extractions happens
inside of the sandbox instead of the container filesystem root.

If you would like to supply your own parameters to `docker run` when launching jobs in docker
containers, you may use the following flags:

    -allow_docker_parameters
    -default_docker_parameters

`-allow_docker_parameters` controls whether or not users may pass their own configuration parameters
through the job configuration files. If set to `false` (the default), the scheduler will reject
jobs with custom parameters. *NOTE*: this setting should be used with caution as it allows any job
owner to specify any parameters they wish, including those that may introduce security concerns
(`privileged=true`, for example).

`-default_docker_parameters` allows a cluster operator to specify a universal set of parameters that
should be used for every container that does not have parameters explicitly configured at the job
level. The argument accepts a multimap format:

    -default_docker_parameters="read-only=true,tmpfs=/tmp,tmpfs=/run"
