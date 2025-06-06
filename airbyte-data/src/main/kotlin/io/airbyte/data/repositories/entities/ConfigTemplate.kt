/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.data.repositories.entities

import com.fasterxml.jackson.databind.JsonNode
import io.micronaut.data.annotation.AutoPopulated
import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.DateUpdated
import io.micronaut.data.annotation.MappedEntity
import jakarta.persistence.Id
import java.util.UUID

@MappedEntity("config_template")
data class ConfigTemplate(
  @field:Id
  @AutoPopulated
  var id: UUID? = null,
  var organizationId: UUID,
  var actorDefinitionId: UUID,
  var partialDefaultConfig: JsonNode,
  var userConfigSpec: JsonNode,
  var tombstone: Boolean = false,
  @DateCreated
  var createdAt: java.time.OffsetDateTime? = null,
  @DateUpdated
  var updatedAt: java.time.OffsetDateTime? = null,
)
