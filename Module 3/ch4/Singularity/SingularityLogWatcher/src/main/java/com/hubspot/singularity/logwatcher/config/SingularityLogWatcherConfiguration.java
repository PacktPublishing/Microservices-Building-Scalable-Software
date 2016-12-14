package com.hubspot.singularity.logwatcher.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.validation.constraints.Min;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.hubspot.singularity.runner.base.configuration.BaseRunnerConfiguration;
import com.hubspot.singularity.runner.base.configuration.Configuration;
import com.hubspot.singularity.runner.base.constraints.DirectoryExists;

@Configuration(filename = "/etc/singularity.logwatcher.yaml", consolidatedField = "logwatcher")
public class SingularityLogWatcherConfiguration extends BaseRunnerConfiguration {
  @Min(1)
  @JsonProperty
  private int byteBufferCapacity = 8192;

  @Min(1)
  @JsonProperty
  private long pollMillis = 1000;

  @NotEmpty
  @JsonProperty
  private String fluentdHosts = "localhost:24224";

  @DirectoryExists
  @JsonProperty
  private String storeDirectory;

  @NotEmpty
  @JsonProperty
  private String storeSuffix = ".store";

  @NotEmpty
  @JsonProperty
  private String fluentdTagPrefix = "forward";

  @Min(1)
  @JsonProperty
  private long retryDelaySeconds = 60;

  public SingularityLogWatcherConfiguration() {
    super(Optional.of("singularity-logwatcher.log"));
  }

  public static class FluentdHost {

    private final String host;
    private final int port;

    public FluentdHost(String host, int port) {
      this.host = host;
      this.port = port;
    }

    public String getHost() {
      return host;
    }

    public int getPort() {
      return port;
    }

    @Override
    public String toString() {
      return "FluentdHost [host=" + host + ", port=" + port + "]";
    }

  }

  private List<FluentdHost> parseFluentdHosts(String fluentdHosts) {
    final String[] split = fluentdHosts.split(",");
    final List<FluentdHost> hosts = Lists.newArrayListWithCapacity(split.length);
    for (String subsplit : split) {
      final String[] hostAndPort = subsplit.split(":");
      hosts.add(new FluentdHost(hostAndPort[0], Integer.parseInt(hostAndPort[1])));
    }
    return hosts;
  }

  public int getByteBufferCapacity() {
    return byteBufferCapacity;
  }

  public void setByteBufferCapacity(int byteBufferCapacity) {
    this.byteBufferCapacity = byteBufferCapacity;
  }

  public long getPollMillis() {
    return pollMillis;
  }

  public void setPollMillis(long pollMillis) {
    this.pollMillis = pollMillis;
  }

  public List<FluentdHost> getFluentdHosts() {
    return parseFluentdHosts(fluentdHosts);
  }

  public void setFluentdHosts(String fluentdHosts) {
    this.fluentdHosts = fluentdHosts;
  }

  public Path getStoreDirectory() {
    return Paths.get(storeDirectory);
  }

  public void setStoreDirectory(String storeDirectory) {
    this.storeDirectory = storeDirectory;
  }

  public String getStoreSuffix() {
    return storeSuffix;
  }

  public void setStoreSuffix(String storeSuffix) {
    this.storeSuffix = storeSuffix;
  }

  public String getFluentdTagPrefix() {
    return fluentdTagPrefix;
  }

  public void setFluentdTagPrefix(String fluentdTagPrefix) {
    this.fluentdTagPrefix = fluentdTagPrefix;
  }

  public long getRetryDelaySeconds() {
    return retryDelaySeconds;
  }

  public void setRetryDelaySeconds(long retryDelaySeconds) {
    this.retryDelaySeconds = retryDelaySeconds;
  }

  @Override
  public String toString() {
    return "SingularityLogWatcherConfiguration[" +
            "byteBufferCapacity=" + byteBufferCapacity +
            ", pollMillis=" + pollMillis +
            ", fluentdHosts='" + fluentdHosts + '\'' +
            ", storeDirectory='" + storeDirectory + '\'' +
            ", storeSuffix='" + storeSuffix + '\'' +
            ", fluentdTagPrefix='" + fluentdTagPrefix + '\'' +
            ", retryDelaySeconds=" + retryDelaySeconds +
            ']';
  }
}
