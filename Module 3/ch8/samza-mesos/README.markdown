Samza on Mesos (Banno fork)
--------------

This project allows to run Samza jobs on Mesos cluster. The Samza jobs can either be packaged in the traditional tarball or in a Docker image.

##Status

Early development. Not tested in production. Hints/issues/PRs are welcome.

##Building

To build and install this package to local repo, run:

    mvn clean install

After this you should be able to reference it like this:

```xml
<dependency>
  <groupId>eu.inn</groupId>
  <artifactId>samza-mesos</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

##Deploying Samza jobs to Marathon

Each Samza job is a Mesos framework. This framework creates one Mesos task for each Samza container. Although not required, it is convenient to use Marathon to run the Samza job's Mesos framework.

###Samza jobs in tarball

Samza jobs are traditionally deployed in a tarball. This archive should contain the following as top-level directories:

- **bin** - contains standard Samza distributed shell scripts (see [hello-samza](https://github.com/apache/incubator-samza-hello-samza))
- **config** - with your job .properties file(s)
- **lib** - contains all .jar files

Example JSON to submit to Marathon to run a Samza job in a tarball may look like this:

```json
{
    "id": "samza-jobs.my-job", 
    "uris": [
        "http://myrepository.com/my-job.tgz"
    ],
    "cmd": "bin/run-job.sh --config-path=file://$PWD/config/my-job.properties --config=job.factory.class=eu.inn.samza.mesos.MesosJobFactory --config=mesos.master.connect=zk://myzookeeper.com:2181/mesos --config=mesos.package.path=http://myrepository.com/my-job.tgz --config=mesos.executor.count=1",
    "cpus": 0.1,
    "mem": 64,
    "instances": 1,
    "env": {
      "JAVA_HEAP_OPTS": "-Xms64M -Xmx64M"
    }
}
```

Note that the `mesos.package.path` provides the location of the tar archive.

This JSON can be submitted to Marathon via curl:

```shell
curl -X POST -H "Content-Type: application/json" -d my-job.json http://mymarathon.com:8080/v2/apps
```

###Samza jobs in Docker

You can also package your Samza jobs in a Docker image, instead of a tarball. The Docker image should have a root `/samza` directory, containing the same `bin`, `config` and `lib` directories as the tarball. Building this Docker image is as simple as building the tarball and then adding it to the image at /samza. In the Samza job config, use `mesos.docker.image` instead of `mesos.package.path`. [banno/samza-mesos](https://registry.hub.docker.com/u/banno/samza-mesos/) provides a convenient base Docker image for you to build your Samza job's Docker image on.

Example JSON to submit to Marathon to run a Samza job in a Docker container may look like this:

```json
{
  "id": "samza-jobs.my-job",
  "container": {
    "docker": {
      "image": "myregistry.com/my-job:latest"
    },
    "type": "DOCKER"
  },
  "cmd": "/samza/bin/run-job.sh --config-path=file:///samza/conf/my-job.properties --config=job.factory.class=eu.inn.samza.mesos.MesosJobFactory --config=mesos.master.connect=zk://myzookeeper.com:2181/mesos --config=mesos.docker.image=myregistry.com/my-job:latest --config=mesos.executor.count=1",
  "cpus": 0.1,
  "mem": 64,
  "instances": 1,
  "env": {
    "JAVA_HEAP_OPTS": "-Xms64M -Xmx64M"
  }
}
```

If your Docker image does not use the standard Samza run-job.sh and run-container.sh startup scripts, but instead uses its own ENTRYPOINT to run either the Samza framework or the Samza container, then you can use the `mesos.docker.entrypoint.arguments` config option.

##Configuration reference

| Property                           | Required? | Default value             | Description                               |
|------------------------------------|-----------|---------------------------|-------------------------------------------|
| mesos.master.connect               | yes       |                           | Mesos master URL                          |
| mesos.package.path                 | yes*      |                           | Job package URI (file, http, hdfs)        |
| mesos.docker.image                 | yes*      |                           | Docker image (registry/my-jobs:latest)    |
| mesos.docker.entrypoint.arguments  |           |                           | Arguments for Docker image ENTRYPOINT     |
| mesos.executor.count               |           | 1                         | Number of Samza containers to run job in  |
| mesos.executor.memory.mb           |           | 1024                      | Mesos task memory constraint              |
| mesos.executor.cpu.cores           |           | 1                         | Mesos task CPU cores constraint           |
| mesos.executor.disk.mb             |           | 1024                      | Mesos task disk constraint                |
| mesos.executor.attributes.*        |           |                           | Slave attributes reqs (regex expressions) |
| mesos.scheduler.user               |           |                           | System user for starting executors        |
| mesos.scheduler.role               |           |                           | Mesos role to use for this scheduler      |
| mesos.scheduler.failover.timeout   |           | a lot (Long.MaxValue)     | Framework failover timeout                |

** either `mesos.package.path` or `mesos.docker.image` is required.

##Acknowledgements

This project is based on Jon Bringhurst's [prototype](https://github.com/fintler/samza/tree/SAMZA-375/samza-mesos).
