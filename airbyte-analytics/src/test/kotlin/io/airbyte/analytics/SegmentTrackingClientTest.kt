/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.analytics

import com.segment.analytics.Analytics
import com.segment.analytics.messages.IdentifyMessage
import com.segment.analytics.messages.TrackMessage
import io.airbyte.commons.version.AirbyteVersion
import io.airbyte.config.Configs
import io.micronaut.context.env.Environment
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.context.ServerRequestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Objects
import java.util.UUID
import java.util.function.Supplier

class SegmentTrackingClientTest {
  private val airbyteVersion = AirbyteVersion("dev")
  private val environment: Environment = mockk()
  private val deploymentId = UUID.randomUUID()
  private val deploymentIdSupplier: Supplier<UUID> = Supplier { deploymentId }
  private val deployment = Deployment(Configs.DeploymentMode.OSS, deploymentIdSupplier, environment)
  private val identity =
    TrackingIdentity(
      airbyteVersion = airbyteVersion,
      customerId = UUID.randomUUID(),
      email = EMAIL,
      anonymousDataCollection = false,
      news = false,
      securityUpdates = true,
    )
  private val workspaceId = UUID.randomUUID()
  private val trackingIdentityFetcher: TrackingIdentityFetcher = mockk()
  private var analytics: Analytics = mockk()
  private var segmentAnalyticsClient: SegmentAnalyticsClient = mockk()
  private lateinit var segmentTrackingClient: SegmentTrackingClient

  @BeforeEach
  fun setup() {
    every { environment.activeNames } returns mutableSetOf("docker")
    every { trackingIdentityFetcher.apply(any()) } returns identity
    every { segmentAnalyticsClient.analyticsClient } returns analytics

    segmentTrackingClient =
      SegmentTrackingClient(
        trackingIdentityFetcher = trackingIdentityFetcher,
        deployment = deployment,
        segmentAnalyticsClient = segmentAnalyticsClient,
        airbyteRole = AIRBYTE_ROLE,
      )
  }

  @Test
  fun testIdentify() {
    val builderSlot = slot<IdentifyMessage.Builder>()
    every { analytics.enqueue(capture(builderSlot)) } returns Unit

    segmentTrackingClient.identify(workspaceId)

    verify(exactly = 1) { analytics.enqueue(any()) }
    val actual = builderSlot.captured.build()
    val expectedTraits: Map<String, Any> =
      mapOf(
        "anonymized" to identity.anonymousDataCollection!!,
        SegmentTrackingClient.AIRBYTE_VERSION_KEY to airbyteVersion.serialize(),
        "deployment_env" to deployment.getDeploymentEnvironment(),
        "deployment_mode" to deployment.deploymentMode,
        "deployment_id" to deployment.deploymentIdSupplier.get(),
        EMAIL_KEY to identity.email!!,
        "subscribed_newsletter" to identity.news!!,
        "subscribed_security" to identity.securityUpdates!!,
        "airbyte_role" to AIRBYTE_ROLE,
      )
    Assertions.assertEquals(identity.customerId.toString(), actual.userId())
    Assertions.assertEquals(expectedTraits, actual.traits())
  }

  @Test
  fun testIdentifyWithRole() {
    segmentTrackingClient =
      SegmentTrackingClient(
        trackingIdentityFetcher = trackingIdentityFetcher,
        deployment = deployment,
        segmentAnalyticsClient = segmentAnalyticsClient,
        airbyteRole = "role",
      )
    val builderSlot = slot<IdentifyMessage.Builder>()
    every { analytics.enqueue(capture(builderSlot)) } returns Unit

    segmentTrackingClient.identify(workspaceId)

    verify(exactly = 1) { analytics.enqueue(any()) }
    val actual = builderSlot.captured.build()
    val expectedTraits: Map<String?, Any?>? =
      mapOf(
        "airbyte_role" to "role",
        SegmentTrackingClient.AIRBYTE_VERSION_KEY to airbyteVersion.serialize(),
        "anonymized" to identity.anonymousDataCollection!!,
        "deployment_env" to deployment.getDeploymentEnvironment(),
        "deployment_mode" to deployment.deploymentMode,
        "deployment_id" to deployment.deploymentIdSupplier.get(),
        EMAIL_KEY to identity.email!!,
        "subscribed_newsletter" to identity.news!!,
        "subscribed_security" to identity.securityUpdates!!,
      )
    Assertions.assertEquals(identity.customerId.toString(), actual.userId())
    Assertions.assertEquals(expectedTraits, actual.traits())
  }

  @Test
  fun testTrack() {
    val builderSlot = slot<TrackMessage.Builder>()
    every { analytics.enqueue(capture(builderSlot)) } returns Unit

    val metadata: Map<String?, Any?>? =
      mapOf(
        SegmentTrackingClient.AIRBYTE_VERSION_KEY to airbyteVersion.serialize(),
        "user_id" to identity.customerId,
        SegmentTrackingClient.AIRBYTE_SOURCE to SegmentTrackingClient.UNKNOWN,
        SegmentTrackingClient.AIRBYTE_DEPLOYMENT_ID to deployment.deploymentIdSupplier.get(),
        SegmentTrackingClient.AIRBYTE_DEPLOYMENT_MODE to deployment.deploymentMode,
      )

    segmentTrackingClient.track(workspaceId, JUMP)

    verify(exactly = 1) { analytics.enqueue(any()) }
    val actual = builderSlot.captured.build()
    Assertions.assertEquals(JUMP, actual.event())
    Assertions.assertEquals(identity.customerId.toString(), actual.userId())
    Assertions.assertEquals(metadata, filterTrackedAtProperty(Objects.requireNonNull(actual.properties())))
  }

  @Test
  fun testTrackWithMetadata() {
    val builderSlot = slot<TrackMessage.Builder>()
    every { analytics.enqueue(capture(builderSlot)) } returns Unit

    val metadata: Map<String?, Any?>? =
      mapOf(
        SegmentTrackingClient.AIRBYTE_VERSION_KEY to airbyteVersion.serialize(),
        EMAIL_KEY to EMAIL,
        "height" to "80 meters",
        "user_id" to identity.customerId,
        SegmentTrackingClient.AIRBYTE_SOURCE to SegmentTrackingClient.UNKNOWN,
        SegmentTrackingClient.AIRBYTE_DEPLOYMENT_ID to deployment.deploymentIdSupplier.get(),
        SegmentTrackingClient.AIRBYTE_DEPLOYMENT_MODE to deployment.deploymentMode,
      )
    segmentTrackingClient.track(workspaceId, JUMP, metadata)
    verify(exactly = 1) { analytics.enqueue(any()) }
    val actual = builderSlot.captured.build()
    Assertions.assertEquals(JUMP, actual.event())
    Assertions.assertEquals(identity.customerId.toString(), actual.userId())
    Assertions.assertEquals(metadata, filterTrackedAtProperty(Objects.requireNonNull(actual.properties())))
  }

  @Test
  fun testTrackAirbyteAnalyticSource() {
    val analyticSource = "test"
    val httpHeaders: HttpHeaders = mockk()
    val httpRequest: HttpRequest<Any> = mockk()
    val builderSlot = slot<TrackMessage.Builder>()

    every { analytics.enqueue(capture(builderSlot)) } returns Unit
    every { httpHeaders.get(SegmentTrackingClient.AIRBYTE_ANALYTIC_SOURCE_HEADER) } returns analyticSource
    every { httpRequest.headers } returns httpHeaders

    ServerRequestContext.set(httpRequest)

    val metadata: Map<String?, Any?>? =
      mapOf(
        SegmentTrackingClient.AIRBYTE_VERSION_KEY to airbyteVersion.serialize(),
        EMAIL_KEY to EMAIL,
        "height" to "80 meters",
        "user_id" to identity.customerId,
        SegmentTrackingClient.AIRBYTE_SOURCE to SegmentTrackingClient.UNKNOWN,
      )
    segmentTrackingClient.track(workspaceId, JUMP, metadata)

    verify(exactly = 1) { analytics.enqueue(any()) }
    val actual = builderSlot.captured.build()
    Assertions.assertEquals(
      analyticSource,
      actual.properties()!![SegmentTrackingClient.AIRBYTE_SOURCE],
    )
  }

  private fun filterTrackedAtProperty(properties: Map<String, *>): MutableMap<String, Any?> {
    val trackedAtKey = "tracked_at"
    Assertions.assertTrue(properties.containsKey(trackedAtKey))
    val builder = mutableMapOf<String, Any?>()
    builder.putAll(properties.filter { e -> trackedAtKey != e.key }.map { e -> Pair(e.key, e.value) }.toMap())
    return builder
  }

  companion object {
    const val AIRBYTE_ROLE = "dev"
    const val EMAIL = "a@airbyte.io"
    const val EMAIL_KEY = "email"
    const val JUMP = "jump"
  }
}
