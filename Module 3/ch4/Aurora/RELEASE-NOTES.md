0.14.0 (Not yet released)
------

### New/updated:

- Added a new optional [Apache Curator](https://curator.apache.org/) backend for performing
  scheduler leader election. You can enable this with the new `-zk_use_curator` scheduler argument.
- Adding --nosetuid-health-checks flag to control whether the executor runs health checks as the job's
  role's user.


0.13.0
------

### New/updated:

- Upgraded Mesos to 0.26.0
- Added a new health endpoint (/leaderhealth) which can be used for load balancer health
  checks to always forward requests to the leading scheduler.
- Added a new `aurora job add` client command to scale out an existing job.
- Upgraded the scheduler ZooKeeper client from 3.4.6 to 3.4.8.
- Added support for dedicated constraints not exclusive to a particular role.
  See [here](docs/features/constraints.md#dedicated-attribute) for more details.
- Added a new argument `--announcer-hostname` to thermos executor to override hostname in service
  registry endpoint. See [here](docs/reference/configuration.md#announcer-objects) for details.
- Descheduling a cron job that was not actually scheduled will no longer return an error.
- Added a new argument `-thermos_home_in_sandbox` to the scheduler for optionally changing
  HOME to the sandbox during thermos executor/runner execution. This is useful in cases
  where the root filesystem inside of the container is read-only, as it moves PEX extraction into
  the sandbox. See [here](docs/operations/configuration.md#docker-containers)
  for more detail.
- Support for ZooKeeper authentication in the executor announcer. See
  [here](docs/operations/security.md#announcer-authentication) for details.
- Scheduler H2 in-memory database is now using
  [MVStore](http://www.h2database.com/html/mvstore.html)
  In addition, scheduler thrift snapshots are now supporting full DB dumps for faster restarts.
- Added scheduler argument `-require_docker_use_executor` that indicates whether the scheduler
  should accept tasks that use the Docker containerizer without an executor (experimental).
- Jobs referencing invalid tier name will be rejected by the scheduler.
- Added a new scheduler argument `--populate_discovery_info`. If set to true, Aurora will start
  to populate DiscoveryInfo field on TaskInfo of Mesos. This could be used for alternative
  service discovery solution like Mesos-DNS.
- Added support for automatic schema upgrades and downgrades when restoring a snapshot that contains
  a DB dump.

### Deprecations and removals:

- Removed deprecated (now redundant) fields:
  - `Identity.role`
  - `TaskConfig.environment`
  - `TaskConfig.jobName`
  - `TaskQuery.owner`
- Removed deprecated `AddInstancesConfig` parameter to `addInstances` RPC.
- Removed deprecated executor argument `-announcer-enable`, which was a no-op in 0.12.0.
- Removed deprecated API constructs related to Locks:
  - removed RPCs that managed locks
    - `acquireLock`
    - `releaseLock`
    - `getLocks`
  - removed `Lock` parameters to RPCs
    - `createJob`
    - `scheduleCronJob`
    - `descheduleCronJob`
    - `restartShards`
    - `killTasks`
    - `addInstances`
    - `replaceCronTemplate`
- Task ID strings are no longer prefixed by a timestamp.
- Changes to the way the scheduler reads command line arguments
  - Removed support for reading command line argument values from files.
  - Removed support for specifying command line argument names with fully-qualified class names.

0.12.0
------

### New/updated:

- Upgraded Mesos to 0.25.0.
- Upgraded the scheduler ZooKeeper client from 3.3.4 to 3.4.6.
- Added support for configuring Mesos role by passing `-mesos_role` to Aurora scheduler at start time.
  This enables resource reservation for Aurora when running in a shared Mesos cluster.
- Aurora task metadata is now mapped to Mesos task labels. Labels are prefixed with
  `org.apache.aurora.metadata.` to prevent clashes with other, external label sources.
- Added new scheduler flag `-default_docker_parameters` to allow a cluster operator to specify a
  universal set of parameters that should be used for every container that does not have parameters
  explicitly configured at the job level.
- Added support for jobs to specify arbitrary ZooKeeper paths for service registration.
  See [here](docs/reference/configuration.md#announcer-objects) for details.
- Log destination is configurable for the thermos runner. See the configuration reference for details
  on how to configure destination per-process. Command line options may also be passed through the
  scheduler in order to configure the global default behavior.
- Env variables can be passed through to task processes by passing `--preserve_env`
  to thermos.
- Changed scheduler logging to use logback.
  Operators wishing to customize logging may do so with standard
  [logback configuration](http://logback.qos.ch/manual/configuration.html)
- When using `--read-json`, aurora can now load multiple jobs from one json file,
  similar to the usual pystachio structure: `{"jobs": [job1, job2, ...]}`. The
  older single-job json format is also still supported.
- `aurora config list` command now supports `--read-json`
- Added scheduler command line argument `-shiro_after_auth_filter`. Optionally specify a class
  implementing javax.servlet.Filter that will be included in the Filter chain following the Shiro
  auth filters.
- The `addInstances` thrift RPC does now increase job instance count (scale out) based on the
  task template pointed by instance `key`.

### Deprecations and removals:

- Deprecated `AddInstancesConfig` argument in `addInstances` thrift RPC.
- Deprecated `TaskQuery` argument in `killTasks` thrift RPC to disallow killing tasks across
  multiple roles. The new safer approach is using `JobKey` with `instances` instead.
- Removed the deprecated field 'ConfigGroup.instanceIds' from the API.
- Removed the following deprecated `HealthCheckConfig` client-side configuration fields: `endpoint`,
  `expected_response`, `expected_response_code`.  These are now set exclusively in like-named fields
  of `HttpHealthChecker.`
- Removed the deprecated 'JobUpdateSettings.maxWaitToInstanceRunningMs' thrift api field (
  UpdateConfig.restart_threshold in client-side configuration). This aspect of job restarts is now
  controlled exclusively via the client with `aurora job restart --restart-threshold=[seconds]`.
- Deprecated executor flag `--announcer-enable`. Enabling the announcer previously required both flags
  `--announcer-enable` and `--announcer-ensemble`, but now only `--announcer-ensemble` must be set.
  `--announcer-enable` is a no-op flag now and will be removed in future version.
- Removed scheduler command line arguments:
  - `-enable_cors_support`.  Enabling CORS is now implicit by setting the argument
    `-enable_cors_for`.
  - `-deduplicate_snapshots` and `-deflate_snapshots`.  These features are good to always enable.
  - `-enable_job_updates` and `-enable_job_creation`
  - `-extra_modules`
  - `-logtostderr`, `-alsologtostderr`, `-vlog`, `-vmodule`, and `use_glog_formatter`. Removed
     in favor of the new logback configuration.


0.11.0
------

### New/updated:

- Upgraded Mesos to 0.24.1.
- Added a new scheduler flag 'framework_announce_principal' to support use of authorization and
  rate limiting in Mesos.
- Added support for shell-based health checkers in addition to HTTP health checkers. In concert with
  this change the `HealthCheckConfig` schema has been restructured to more cleanly allow configuring
  varied health checkers.
- Added support for taking in an executor configuration in JSON via a command line argument
  `--custom_executor_config` which will override all other the command line arguments and default
  values pertaining to the executor.
- Log rotation has been added to the thermos runner. See the configuration reference for details
  on how configure rotation per-process. Command line options may also be passed through the
  scheduler in order to configure the global default behavior.

### Deprecations and removals:

- The client-side updater has been removed, along with the CLI commands that used it:
  'aurora job update' and 'aurora job cancel-update'.  Users are encouraged to take
  advantage of scheduler-driven updates (see 'aurora update -h' for usage), which has been a
  stable feature for several releases.
- The following fields from `HealthCheckConfig` are now deprecated:
  `endpoint`, `expected_response`, `expected_response_code` in favor of setting them as part of an
  `HttpHealthChecker.`
- The field 'JobUpdateSettings.maxWaitToInstanceRunningMs' (UpdateConfig.restart_threshold in
  client-side configuration) is now deprecated.  This setting was brittle in practice, and is
  ignored by the 0.11.0 scheduler.


0.10.0
------

### New/updated:

- Upgraded Mesos to 0.23.0. NOTE: Aurora executor now requires openssl runtime dependencies that
  were not previously enforced. You will need libcurl available on every Mesos slave (or Docker
  container) to successfully launch Aurora executor.  See [here](docs/getting-started.md) for more
  details on Mesos runtime dependencies.
- Resource quota is no longer consumed by production jobs with a dedicated constraint (AURORA-1457).
- The Python build layout has changed:
  * The `apache.thermos` package has been removed.
  * The `apache.gen.aurora` package has been renamed to `apache.aurora.thrift`.
  * The `apache.gen.thermos` package has been renamed to `apache.thermos.thrift`.
  * A new `apache.thermos.runner` package has been introduced, providing the `thermos_runner`
    binary.
  * A new `apache.aurora.kerberos` package has been introduced, containing the Kerberos-supporting
    versions of `aurora` and `aurora_admin` (`kaurora` and `kaurora_admin`).
  * Most BUILD targets under `src/main` have been removed, see [here](http://s.apache.org/b8z) for
    details.

### Deprecations and removals:

- Removed the `--root` option from the observer.
- Thrift `ConfigGroup.instanceIds` field has been deprecated. Use ConfigGroup.instances instead.
- Deprecated `SessionValidator` and `CapabilityValidator` interfaces have been removed. All
  `SessionKey`-typed arguments are now nullable and ignored by the scheduler Thrift API.


0.9.0
-----

- Now requires JRE 8 or greater.
- GC executor is fully replaced by the task state reconciliation (AURORA-1047).
- The scheduler command line argument `-enable_legacy_constraints` has been
  removed, and the scheduler no longer automatically injects `host` and `rack`
  constraints for production services. (AURORA-1074)
- SLA metrics for non-production jobs have been disabled by default. They can
  be enabled via the scheduler command line. Metric names have changed from
  `...nonprod_ms` to `...ms_nonprod` (AURORA-1350).


0.8.0
-----

- A new command line argument was added to the observer: `--mesos-root`
  This must point to the same path as `--work_dir` on the mesos slave.
- Build targets for thermos and observer have changed, they are now:
  * `src/main/python/apache/aurora/tools:thermos`
  * `src/main/python/apache/aurora/tools:thermos_observer`
