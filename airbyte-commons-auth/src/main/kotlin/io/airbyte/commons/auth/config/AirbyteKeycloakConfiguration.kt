/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.auth.config

import io.micronaut.context.annotation.ConfigurationProperties

/**
 * This class bundles all internal Keycloak configuration into a convenient singleton that can be
 * consumed by multiple Micronaut applications. Each application still needs to define each property
 * in application.yml. TODO figure out how to avoid redundant configs in each consuming
 * application.yml
 */
@ConfigurationProperties("airbyte.keycloak")
class AirbyteKeycloakConfiguration {
  var protocol: String = ""
  var host: String = ""
  var basePath: String = ""
  var airbyteRealm: String = ""
  var realm: String = ""
  var clientRealm: String = ""
  var internalRealm: String = ""
  var clientId: String = ""
  var webClientId: String = ""
  var username: String = ""
  var password: String = ""
  var resetRealm: Boolean = false

  fun getKeycloakUserInfoEndpointForRealm(realm: String): String {
    val hostWithoutTrailingSlash = if (host.endsWith("/")) host.substring(0, host.length - 1) else host
    val basePathWithLeadingSlash = if (basePath.startsWith("/")) basePath else "/$basePath"
    val keycloakUserInfoURI = "/protocol/openid-connect/userinfo"
    return "$protocol://$hostWithoutTrailingSlash$basePathWithLeadingSlash/realms/$realm$keycloakUserInfoURI"
  }

  fun getServerUrl(): String = "$protocol://$host$basePath"
}
