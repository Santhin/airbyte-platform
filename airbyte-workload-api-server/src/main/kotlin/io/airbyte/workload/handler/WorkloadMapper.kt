package io.airbyte.workload.handler

import io.airbyte.db.instance.configs.jooq.generated.enums.WorkloadStatus
import io.airbyte.db.instance.configs.jooq.generated.enums.WorkloadType
import io.airbyte.workload.repository.domain.Workload
import io.airbyte.workload.repository.domain.WorkloadLabel

typealias DomainWorkloadStatus = WorkloadStatus
typealias ApiWorkloadStatus = io.airbyte.workload.api.domain.WorkloadStatus
typealias DomainWorkload = Workload
typealias ApiWorkload = io.airbyte.workload.api.domain.Workload
typealias DomainWorkloadLabel = WorkloadLabel
typealias ApiWorkloadLabel = io.airbyte.workload.api.domain.WorkloadLabel
typealias DomainWorkloadType = WorkloadType
typealias ApiWorkloadType = io.airbyte.workload.api.domain.WorkloadType

fun ApiWorkloadStatus.toDomain(): DomainWorkloadStatus {
  return when (this) {
    ApiWorkloadStatus.PENDING -> DomainWorkloadStatus.pending
    ApiWorkloadStatus.CLAIMED -> DomainWorkloadStatus.claimed
    ApiWorkloadStatus.RUNNING -> DomainWorkloadStatus.running
    ApiWorkloadStatus.SUCCESS -> DomainWorkloadStatus.success
    ApiWorkloadStatus.FAILURE -> DomainWorkloadStatus.failure
    ApiWorkloadStatus.CANCELLED -> DomainWorkloadStatus.cancelled
  }
}

fun DomainWorkloadStatus.toApi(): ApiWorkloadStatus {
  return when (this) {
    DomainWorkloadStatus.pending -> ApiWorkloadStatus.PENDING
    DomainWorkloadStatus.claimed -> ApiWorkloadStatus.CLAIMED
    DomainWorkloadStatus.running -> ApiWorkloadStatus.RUNNING
    DomainWorkloadStatus.success -> ApiWorkloadStatus.SUCCESS
    DomainWorkloadStatus.failure -> ApiWorkloadStatus.FAILURE
    DomainWorkloadStatus.cancelled -> ApiWorkloadStatus.CANCELLED
  }
}

fun ApiWorkloadType.toDomain(): DomainWorkloadType {
  return when (this) {
    ApiWorkloadType.CHECK -> DomainWorkloadType.check
    ApiWorkloadType.DISCOVER -> DomainWorkloadType.discover
    ApiWorkloadType.SPEC -> DomainWorkloadType.spec
    ApiWorkloadType.SYNC -> DomainWorkloadType.sync
  }
}

fun DomainWorkloadType.toApi(): ApiWorkloadType {
  return when (this) {
    DomainWorkloadType.check -> ApiWorkloadType.CHECK
    DomainWorkloadType.discover -> ApiWorkloadType.DISCOVER
    DomainWorkloadType.spec -> ApiWorkloadType.SPEC
    DomainWorkloadType.sync -> ApiWorkloadType.SYNC
  }
}

fun DomainWorkload.toApi(): ApiWorkload {
  return ApiWorkload(
    id = this.id,
    dataplaneId = this.dataplaneId,
    status = this.status.toApi(),
    labels = this.workloadLabels?.map { it.toApi() }?.toMutableList() ?: mutableListOf(),
    inputPayload = this.inputPayload,
    logPath = this.logPath,
    geography = this.geography,
    mutexKey = this.mutexKey,
    type = this.type.toApi(),
  )
}

fun ApiWorkload.toDomain(): DomainWorkload {
  return DomainWorkload(
    id = this.id,
    dataplaneId = this.dataplaneId,
    status = this.status?.toDomain() ?: WorkloadStatus.pending,
    workloadLabels = this.labels?.map { it.toDomain() },
    inputPayload = this.inputPayload,
    logPath = this.logPath,
    geography = this.geography,
    mutexKey = this.mutexKey,
    type = this.type.toDomain(),
  )
}

fun DomainWorkloadLabel.toApi(): ApiWorkloadLabel {
  return ApiWorkloadLabel(
    key = this.key,
    value = this.key,
  )
}

fun ApiWorkloadLabel.toDomain(): DomainWorkloadLabel {
  return DomainWorkloadLabel(
    key = this.key,
    value = this.value,
  )
}
