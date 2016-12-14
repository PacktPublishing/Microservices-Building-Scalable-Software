package com.hubspot.singularity.executor.task;

import java.lang.ProcessBuilder.Redirect;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.hubspot.singularity.SingularityS3FormatHelper;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.executor.TemplateManager;
import com.hubspot.singularity.executor.config.SingularityExecutorConfiguration;
import com.hubspot.singularity.executor.config.SingularityExecutorS3UploaderAdditionalFile;
import com.hubspot.singularity.executor.models.LogrotateTemplateContext;
import com.hubspot.singularity.runner.base.configuration.SingularityRunnerBaseConfiguration;
import com.hubspot.singularity.runner.base.shared.JsonObjectFileHelper;
import com.hubspot.singularity.runner.base.shared.S3UploadMetadata;
import com.hubspot.singularity.runner.base.shared.SimpleProcessManager;
import com.hubspot.singularity.runner.base.shared.TailMetadata;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class SingularityExecutorTaskLogManager {

  private final SingularityExecutorTaskDefinition taskDefinition;
  private final TemplateManager templateManager;
  private final SingularityRunnerBaseConfiguration baseConfiguration;
  private final SingularityExecutorConfiguration configuration;
  private final Logger log;
  private final JsonObjectFileHelper jsonObjectFileHelper;

  public SingularityExecutorTaskLogManager(SingularityExecutorTaskDefinition taskDefinition, TemplateManager templateManager, SingularityRunnerBaseConfiguration baseConfiguration, SingularityExecutorConfiguration configuration, Logger log, JsonObjectFileHelper jsonObjectFileHelper) {
    this.log = log;
    this.taskDefinition = taskDefinition;
    this.templateManager = templateManager;
    this.configuration = configuration;
    this.baseConfiguration = baseConfiguration;
    this.jsonObjectFileHelper = jsonObjectFileHelper;
  }

  public void setup() {
    ensureServiceOutExists();
    writeLogrotateFile();
    writeTailMetadata(false);
    writeS3MetadataFileForRotatedFiles(false);
  }

  @SuppressFBWarnings
  private boolean writeS3MetadataFileForRotatedFiles(boolean finished) {
    final Path serviceLogOutPath = taskDefinition.getServiceLogOutPath();
    final Path serviceLogParent = serviceLogOutPath.getParent();
    final Path logrotateDirectory = serviceLogParent.resolve(configuration.getLogrotateToDirectory());

    boolean result = writeS3MetadataFile("default", logrotateDirectory, String.format("%s*.gz*", taskDefinition.getServiceLogOutPath().getFileName()), Optional.<String>absent(), Optional.<String>absent(), finished);

    int index = 1;
    for (SingularityExecutorS3UploaderAdditionalFile additionalFile : configuration.getS3UploaderAdditionalFiles()) {
      result = result && writeS3MetadataFile(additionalFile.getS3UploaderFilenameHint().or(String.format("extra%d", index)), logrotateDirectory, String.format("%s*.gz*", additionalFile.getFilename()), additionalFile.getS3UploaderBucket(), additionalFile.getS3UploaderKeyPattern(), finished);
      index++;
    }

    return result;
  }

  private void writeLogrotateFile() {
    log.info("Writing logrotate configuration file to {}", getLogrotateConfPath());
    templateManager.writeLogrotateFile(getLogrotateConfPath(), new LogrotateTemplateContext(configuration, taskDefinition));
  }

  @SuppressFBWarnings
  public boolean teardown() {
    boolean writeTailMetadataSuccess = writeTailMetadata(true);

    ensureServiceOutExists();

    if (taskDefinition.shouldLogrotateLogFile()) {
      copyLogTail();
    }

    boolean writeS3MetadataForNonLogRotatedFileSuccess = true;

    if (!taskDefinition.shouldLogrotateLogFile()) {
      writeS3MetadataForNonLogRotatedFileSuccess = writeS3MetadataFile("unrotated", taskDefinition.getServiceLogOutPath().getParent(), taskDefinition.getServiceLogOutPath().getFileName().toString(), Optional.<String>absent(), Optional.<String>absent(), true);
    }

    if (manualLogrotate()) {
      boolean removeLogRotateFileSuccess = removeLogrotateFile();

      removeEmptyServiceOut();

      boolean writeS3MetadataForLogrotatedFilesSuccess = writeS3MetadataFileForRotatedFiles(true);

      return writeTailMetadataSuccess && removeLogRotateFileSuccess && writeS3MetadataForLogrotatedFilesSuccess && writeS3MetadataForNonLogRotatedFileSuccess;
    } else {
      return false;
    }
  }

  private void copyLogTail() {
    if (configuration.getTailLogLinesToSave() <= 0) {
      return;
    }

    final Path tailOfLogPath = taskDefinition.getTaskDirectoryPath().resolve(configuration.getServiceFinishedTailLog());

    if (Files.exists(tailOfLogPath)) {
      log.debug("{} already existed, skipping tail", tailOfLogPath);
      return;
    }

    final List<String> cmd = ImmutableList.of(
        "tail",
        "-n",
        Integer.toString(configuration.getTailLogLinesToSave()),
        taskDefinition.getServiceLogOut());

    try {
      new SimpleProcessManager(log).runCommand(cmd, Redirect.to(tailOfLogPath.toFile()));
    } catch (Throwable t) {
      log.error("Failed saving tail of log {} to {}", taskDefinition.getServiceLogOut(), configuration.getServiceFinishedTailLog(), t);
    }
  }

  public boolean removeLogrotateFile() {
    boolean deleted = false;
    try {
      deleted = Files.deleteIfExists(getLogrotateConfPath());
    } catch (Throwable t) {
      log.trace("Couldn't delete {}", getLogrotateConfPath(), t);
      return false;
    }
    log.trace("Deleted {} : {}", getLogrotateConfPath(), deleted);
    return true;
  }

  public boolean manualLogrotate() {
    if (!Files.exists(getLogrotateConfPath())) {
      log.info("{} did not exist, skipping manual logrotation", getLogrotateConfPath());
      return true;
    }

    final List<String> command = ImmutableList.of(
        configuration.getLogrotateCommand(),
        "-f",
        "-s",
        taskDefinition.getLogrotateStateFilePath().toString(),
        getLogrotateConfPath().toString());

    try {
      new SimpleProcessManager(log).runCommand(command);
      return true;
    } catch (Throwable t) {
      log.warn("Tried to manually logrotate using {}, but caught", getLogrotateConfPath(), t);
      return false;
    }
  }

  private void ensureServiceOutExists() {
    try {
      if (!Files.exists(taskDefinition.getServiceLogOutPath())) {
        Files.createFile(taskDefinition.getServiceLogOutPath());
      }
    } catch (FileAlreadyExistsException faee) {
      log.debug("Executor out {} already existed", taskDefinition.getServiceLogOut());
    } catch (Throwable t) {
      log.error("Failed creating executor out {}", taskDefinition.getServiceLogOut(), t);
    }
  }

  private void removeEmptyServiceOut() {
    try {
      if (Files.exists(taskDefinition.getServiceLogOutPath()) && Files.size(taskDefinition.getServiceLogOutPath()) == 0) {
        Files.deleteIfExists(taskDefinition.getServiceLogOutPath());
      }
    } catch (Throwable t) {
      log.error("Failed checking/deleting executor out {}", taskDefinition.getServiceLogOut(), t);
    }
  }

  private boolean writeTailMetadata(boolean finished) {
    if (!taskDefinition.getExecutorData().getLoggingTag().isPresent()) {
      if (!finished) {
        log.warn("Not writing logging metadata because logging tag is absent");
      }
      return true;
    }

    final TailMetadata tailMetadata = new TailMetadata(taskDefinition.getServiceLogOut(), taskDefinition.getExecutorData().getLoggingTag().get(), taskDefinition.getExecutorData().getLoggingExtraFields(), finished);
    final Path path = TailMetadata.getTailMetadataPath(Paths.get(baseConfiguration.getLogWatcherMetadataDirectory()), baseConfiguration.getLogWatcherMetadataSuffix(), tailMetadata);

    return jsonObjectFileHelper.writeObject(tailMetadata, path, log);
  }

  private String getS3KeyPattern(String s3KeyPattern) {
    final SingularityTaskId singularityTaskId = getSingularityTaskId();

    return SingularityS3FormatHelper.getS3KeyFormat(s3KeyPattern, singularityTaskId, taskDefinition.getExecutorData().getLoggingTag());
  }

  private SingularityTaskId getSingularityTaskId() {
    return SingularityTaskId.valueOf(taskDefinition.getTaskId());
  }

  public Path getLogrotateConfPath() {
    return Paths.get(configuration.getLogrotateConfDirectory()).resolve(taskDefinition.getTaskId());
  }

  private boolean writeS3MetadataFile(String filenameHint, Path pathToS3Directory, String globForS3Files, Optional<String> s3Bucket, Optional<String> s3KeyPattern, boolean finished) {
    final String s3UploaderBucket = taskDefinition.getExecutorData().getLoggingS3Bucket().or(configuration.getS3UploaderBucket());

    S3UploadMetadata s3UploadMetadata = new S3UploadMetadata(pathToS3Directory.toString(), globForS3Files, s3Bucket.or(s3UploaderBucket), getS3KeyPattern(s3KeyPattern.or(configuration.getS3UploaderKeyPattern())), finished, Optional.<String> absent(),
        Optional.<Integer> absent(), Optional.<String> absent(), Optional.<String> absent(), Optional.<Long> absent());

    String s3UploadMetadataFileName = String.format("%s-%s%s", taskDefinition.getTaskId(), filenameHint, baseConfiguration.getS3UploaderMetadataSuffix());

    Path s3UploadMetadataPath = Paths.get(baseConfiguration.getS3UploaderMetadataDirectory()).resolve(s3UploadMetadataFileName);

    return jsonObjectFileHelper.writeObject(s3UploadMetadata, s3UploadMetadataPath, log);
  }

}
