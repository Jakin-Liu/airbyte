/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.persistence.job.tracker;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.config.AttemptSyncConfig;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.StandardCheckConnectionOutput;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSyncOperation;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.persistence.job.JobPersistence;
import io.airbyte.persistence.job.WorkspaceHelper;
import io.airbyte.persistence.job.models.Attempt;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.validation.json.JsonSchemaValidator;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;

public class JobTracker {

  public enum JobState {
    STARTED,
    SUCCEEDED,
    FAILED
  }

  public static final String MESSAGE_NAME = "Connector Jobs";
  public static final String CONFIG = "config";
  public static final String CATALOG = "catalog";
  public static final String OPERATION = "operation.";
  public static final String SET = "set";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ConfigRepository configRepository;
  private final JobPersistence jobPersistence;
  private final WorkspaceHelper workspaceHelper;
  private final TrackingClient trackingClient;

  public JobTracker(final ConfigRepository configRepository, final JobPersistence jobPersistence, final TrackingClient trackingClient) {
    this(configRepository, jobPersistence, new WorkspaceHelper(configRepository, jobPersistence), trackingClient);
  }

  @VisibleForTesting
  JobTracker(final ConfigRepository configRepository,
             final JobPersistence jobPersistence,
             final WorkspaceHelper workspaceHelper,
             final TrackingClient trackingClient) {
    this.configRepository = configRepository;
    this.jobPersistence = jobPersistence;
    this.workspaceHelper = workspaceHelper;
    this.trackingClient = trackingClient;
  }

  public void trackCheckConnectionSource(final UUID jobId,
                                         final UUID sourceDefinitionId,
                                         final UUID workspaceId,
                                         final JobState jobState,
                                         final StandardCheckConnectionOutput output) {
    Exceptions.swallow(() -> {
      final Map<String, Object> checkConnMetadata = generateCheckConnectionMetadata(output);
      final Map<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.CHECK_CONNECTION_SOURCE);
      final Map<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinitionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(checkConnMetadata, jobMetadata, sourceDefMetadata, stateMetadata));
    });
  }

  public void trackCheckConnectionDestination(final UUID jobId,
                                              final UUID destinationDefinitionId,
                                              final UUID workspaceId,
                                              final JobState jobState,
                                              final StandardCheckConnectionOutput output) {
    Exceptions.swallow(() -> {
      final Map<String, Object> checkConnMetadata = generateCheckConnectionMetadata(output);
      final Map<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.CHECK_CONNECTION_DESTINATION);
      final Map<String, Object> destinationDefinitionMetadata = generateDestinationDefinitionMetadata(destinationDefinitionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(checkConnMetadata, jobMetadata, destinationDefinitionMetadata, stateMetadata));
    });
  }

  public void trackDiscover(final UUID jobId, final UUID sourceDefinitionId, final UUID workspaceId, final JobState jobState) {
    Exceptions.swallow(() -> {
      final Map<String, Object> jobMetadata = generateJobMetadata(jobId.toString(), ConfigType.DISCOVER_SCHEMA);
      final Map<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinitionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);

      track(workspaceId, MoreMaps.merge(jobMetadata, sourceDefMetadata, stateMetadata));
    });
  }

  // used for tracking all asynchronous jobs (sync and reset).
  public void trackSync(final Job job, final JobState jobState) {
    Exceptions.swallow(() -> {
      final ConfigType configType = job.getConfigType();
      final boolean allowedJob = configType == ConfigType.SYNC || configType == ConfigType.RESET_CONNECTION;
      Preconditions.checkArgument(allowedJob, "Job type " + configType + " is not allowed!");
      final long jobId = job.getId();
      final Optional<Attempt> lastAttempt = job.getLastAttempt();
      final Optional<AttemptSyncConfig> attemptSyncConfig = lastAttempt.flatMap(Attempt::getSyncConfig);

      final UUID connectionId = UUID.fromString(job.getScope());
      final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
      final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);

      final Map<String, Object> jobMetadata = generateJobMetadata(String.valueOf(jobId), configType, job.getAttemptsCount());
      final Map<String, Object> jobAttemptMetadata = generateJobAttemptMetadata(jobId, jobState);
      final Map<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinition);
      final Map<String, Object> destinationDefMetadata = generateDestinationDefinitionMetadata(destinationDefinition);
      final Map<String, Object> syncMetadata = generateSyncMetadata(connectionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);
      final Map<String, Object> syncConfigMetadata = generateSyncConfigMetadata(
          job.getConfig(),
          attemptSyncConfig.orElse(null),
          sourceDefinition.getSpec().getConnectionSpecification(),
          destinationDefinition.getSpec().getConnectionSpecification());

      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(jobId);
      track(workspaceId,
          MoreMaps.merge(
              jobMetadata,
              jobAttemptMetadata,
              sourceDefMetadata,
              destinationDefMetadata,
              syncMetadata,
              stateMetadata,
              syncConfigMetadata));
    });
  }

  public void trackSyncForInternalFailure(final Long jobId,
                                          final UUID connectionId,
                                          final Integer attempts,
                                          final JobState jobState,
                                          final Exception e) {
    Exceptions.swallow(() -> {
      final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
      final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);

      final Map<String, Object> jobMetadata = generateJobMetadata(String.valueOf(jobId), null, attempts);
      final Map<String, Object> jobAttemptMetadata = generateJobAttemptMetadata(jobId, jobState);
      final Map<String, Object> sourceDefMetadata = generateSourceDefinitionMetadata(sourceDefinition);
      final Map<String, Object> destinationDefMetadata = generateDestinationDefinitionMetadata(destinationDefinition);
      final Map<String, Object> syncMetadata = generateSyncMetadata(connectionId);
      final Map<String, Object> stateMetadata = generateStateMetadata(jobState);
      final Map<String, Object> generalMetadata = Map.of("connection_id", connectionId, "internal_error_cause", e.getMessage(),
          "internal_error_type", e.getClass().getName());

      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(jobId);

      track(workspaceId,
          MoreMaps.merge(
              jobMetadata,
              jobAttemptMetadata,
              sourceDefMetadata,
              destinationDefMetadata,
              syncMetadata,
              stateMetadata,
              generalMetadata));
    });
  }

  private Map<String, Object> generateSyncConfigMetadata(
                                                         final JobConfig config,
                                                         @Nullable final AttemptSyncConfig attemptSyncConfig,
                                                         final JsonNode sourceConfigSchema,
                                                         final JsonNode destinationConfigSchema) {
    if (config.getConfigType() == ConfigType.SYNC) {
      final Map<String, Object> actorConfigMetadata = new HashMap<>();

      if (attemptSyncConfig != null) {
        final JsonNode sourceConfiguration = attemptSyncConfig.getSourceConfiguration();
        final JsonNode destinationConfiguration = attemptSyncConfig.getDestinationConfiguration();

        final Map<String, Object> sourceMetadata = configToMetadata(CONFIG + ".source", sourceConfiguration, sourceConfigSchema);
        final Map<String, Object> destinationMetadata = configToMetadata(CONFIG + ".destination", destinationConfiguration, destinationConfigSchema);

        actorConfigMetadata.putAll(sourceMetadata);
        actorConfigMetadata.putAll(destinationMetadata);
      }

      final Map<String, Object> catalogMetadata = getCatalogMetadata(config.getSync().getConfiguredAirbyteCatalog());
      return MoreMaps.merge(actorConfigMetadata, catalogMetadata);
    } else {
      return emptyMap();
    }
  }

  private Map<String, Object> getCatalogMetadata(final ConfiguredAirbyteCatalog catalog) {
    final Map<String, Object> output = new HashMap<>();

    for (final ConfiguredAirbyteStream stream : catalog.getStreams()) {
      output.put(CATALOG + ".sync_mode." + stream.getSyncMode().name().toLowerCase(), SET);
      output.put(CATALOG + ".destination_sync_mode." + stream.getDestinationSyncMode().name().toLowerCase(), SET);
    }

    return output;
  }

  /**
   * Flattens a config into a map. Uses the schema to determine which fields are const (i.e.
   * non-sensitive). Non-const, non-boolean values are replaced with {@link #SET} to avoid leaking
   * potentially-sensitive information.
   * <p>
   * anyOf/allOf schemas are treated as non-const values. These aren't (currently) used in config
   * schemas anyway.
   *
   * @param jsonPath A prefix to add to all the keys in the returned map, with a period (`.`)
   *        separator
   * @param schema The JSON schema that {@code config} conforms to
   */
  protected static Map<String, Object> configToMetadata(final String jsonPath, final JsonNode config, final JsonNode schema) {
    final Map<String, Object> metadata = configToMetadata(config, schema);
    // Prepend all the keys with the root jsonPath
    // But leave the values unchanged
    final Map<String, Object> output = new HashMap<>();
    Jsons.mergeMaps(output, jsonPath, metadata);
    return output;
  }

  /**
   * Does the actually interesting bits of configToMetadata. If config is an object, returns a
   * flattened map. If config is _not_ an object (i.e. it's a primitive string/number/etc, or it's an
   * array) then returns a map of {null: toMetadataValue(config)}.
   */
  @SuppressWarnings("PMD.ForLoopCanBeForeach")
  private static Map<String, Object> configToMetadata(final JsonNode config, final JsonNode schema) {
    if (schema.hasNonNull("const") || schema.hasNonNull("enum")) {
      // If this schema is a const or an enum, then just dump it into a map:
      // * If it's an object, flatten it
      // * Otherwise, do some basic conversions to value-ish data.
      // It would be a weird thing to declare const: null, but in that case we don't want to report null
      // anyway, so explicitly use hasNonNull.
      return Jsons.flatten(config);
    } else if (schema.has("oneOf")) {
      // If this schema is a oneOf, then find the first sub-schema which the config matches
      // and use that sub-schema to convert the config to a map
      final JsonSchemaValidator validator = new JsonSchemaValidator();
      for (final Iterator<JsonNode> it = schema.get("oneOf").elements(); it.hasNext();) {
        final JsonNode subSchema = it.next();
        if (validator.test(subSchema, config)) {
          return configToMetadata(config, subSchema);
        }
      }
      // If we didn't match any of the subschemas, then something is wrong. Bail out silently.
      return emptyMap();
    } else if (config.isObject()) {
      // If the schema is not a oneOf, but the config is an object (i.e. the schema has "type": "object")
      // then we need to recursively convert each field of the object to a map.
      final Map<String, Object> output = new HashMap<>();
      final JsonNode maybeProperties = schema.get("properties");

      // If additionalProperties is not set, or it's a boolean, then there's no schema for additional
      // properties. Use the accept-all schema.
      // Otherwise, it's an actual schema.
      final JsonNode maybeAdditionalProperties = schema.get("additionalProperties");
      final JsonNode additionalPropertiesSchema;
      if (maybeAdditionalProperties == null || maybeAdditionalProperties.isBoolean()) {
        additionalPropertiesSchema = OBJECT_MAPPER.createObjectNode();
      } else {
        additionalPropertiesSchema = maybeAdditionalProperties;
      }

      for (final Iterator<Entry<String, JsonNode>> it = config.fields(); it.hasNext();) {
        final Entry<String, JsonNode> entry = it.next();
        final String field = entry.getKey();
        final JsonNode value = entry.getValue();

        final JsonNode propertySchema;
        if (maybeProperties != null && maybeProperties.hasNonNull(field)) {
          // If this property is explicitly declared, then use its schema
          propertySchema = maybeProperties.get(field);
        } else {
          // otherwise, use the additionalProperties schema
          propertySchema = additionalPropertiesSchema;
        }

        Jsons.mergeMaps(output, field, configToMetadata(value, propertySchema));
      }
      return output;
    } else if (config.isBoolean()) {
      return singletonMap(null, config.asBoolean());
    } else if ((!config.isTextual() && !config.isNull()) || (config.isTextual() && !config.asText().isEmpty())) {
      // This is either non-textual (e.g. integer, array, etc) or non-empty text
      return singletonMap(null, SET);
    } else {
      // Otherwise, this is an empty string, so just ignore it
      return emptyMap();
    }
  }

  private Map<String, Object> generateSyncMetadata(final UUID connectionId) throws ConfigNotFoundException, IOException, JsonValidationException {
    final Map<String, Object> operationUsage = new HashMap<>();
    final StandardSync standardSync = configRepository.getStandardSync(connectionId);
    for (final UUID operationId : standardSync.getOperationIds()) {
      final StandardSyncOperation operation = configRepository.getStandardSyncOperation(operationId);
      if (operation != null) {
        final Integer usageCount = (Integer) operationUsage.getOrDefault(OPERATION + operation.getOperatorType(), 0);
        operationUsage.put(OPERATION + operation.getOperatorType(), usageCount + 1);
      }
    }

    final Map<String, Object> streamCountData = new HashMap<>();
    final Integer streamCount = standardSync.getCatalog().getStreams().size();
    streamCountData.put("number_of_streams", streamCount);

    return MoreMaps.merge(TrackingMetadata.generateSyncMetadata(standardSync), operationUsage, streamCountData);
  }

  private static Map<String, Object> generateStateMetadata(final JobState jobState) {
    final Map<String, Object> metadata = new HashMap<>();

    if (JobState.STARTED.equals(jobState)) {
      metadata.put("attempt_stage", "STARTED");
    } else if (List.of(JobState.SUCCEEDED, JobState.FAILED).contains(jobState)) {
      metadata.put("attempt_stage", "ENDED");
      metadata.put("attempt_completion_status", jobState);
    }

    return Collections.unmodifiableMap(metadata);
  }

  /**
   * The CheckConnection jobs (both source and destination) of the
   * {@link io.airbyte.scheduler.client.SynchronousSchedulerClient} interface can have a successful
   * job with a failed check. Because of this, tracking just the job attempt status does not capture
   * the whole picture. The `check_connection_outcome` field tracks this.
   */
  private Map<String, Object> generateCheckConnectionMetadata(final StandardCheckConnectionOutput output) {
    if (output == null) {
      return Map.of();
    }
    return Map.of("check_connection_outcome", output.getStatus().toString());
  }

  private Map<String, Object> generateDestinationDefinitionMetadata(final UUID destinationDefinitionId)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardDestinationDefinition destinationDefinition = configRepository.getStandardDestinationDefinition(destinationDefinitionId);
    return generateDestinationDefinitionMetadata(destinationDefinition);
  }

  private Map<String, Object> generateDestinationDefinitionMetadata(final StandardDestinationDefinition destinationDefinition) {
    return TrackingMetadata.generateDestinationDefinitionMetadata(destinationDefinition);
  }

  private Map<String, Object> generateSourceDefinitionMetadata(final UUID sourceDefinitionId)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardSourceDefinition sourceDefinition = configRepository.getStandardSourceDefinition(sourceDefinitionId);
    return generateSourceDefinitionMetadata(sourceDefinition);
  }

  private Map<String, Object> generateSourceDefinitionMetadata(final StandardSourceDefinition sourceDefinition) {
    return TrackingMetadata.generateSourceDefinitionMetadata(sourceDefinition);
  }

  private Map<String, Object> generateJobMetadata(final String jobId, final ConfigType configType) {
    return generateJobMetadata(jobId, configType, 0);
  }

  private Map<String, Object> generateJobMetadata(final String jobId, final ConfigType configType, final int attempt) {
    final Map<String, Object> metadata = new HashMap<>();
    if (configType != null) {
      metadata.put("job_type", configType);
    }
    metadata.put("job_id", jobId);
    metadata.put("attempt_id", attempt);

    return Collections.unmodifiableMap(metadata);
  }

  private Map<String, Object> generateJobAttemptMetadata(final long jobId, final JobState jobState) throws IOException {
    final Job job = jobPersistence.getJob(jobId);
    if (jobState != JobState.STARTED) {
      return TrackingMetadata.generateJobAttemptMetadata(job);
    } else {
      return Map.of();
    }
  }

  private void track(final UUID workspaceId, final Map<String, Object> metadata)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    // unfortunate but in the case of jobs that cannot be linked to a workspace there not a sensible way
    // track it.
    if (workspaceId != null) {
      final StandardWorkspace standardWorkspace = configRepository.getStandardWorkspaceNoSecrets(workspaceId, true);
      if (standardWorkspace != null && standardWorkspace.getName() != null) {
        final Map<String, Object> standardTrackingMetadata = Map.of(
            "workspace_id", workspaceId,
            "workspace_name", standardWorkspace.getName());

        trackingClient.track(workspaceId, MESSAGE_NAME, MoreMaps.merge(metadata, standardTrackingMetadata));
      }
    }
  }

}
