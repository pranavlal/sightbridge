package com.sightbridge.core.health

enum class HealthLevel {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    RECOVERING,
    DISABLED
}

data class ComponentHealth(
    val component: String,
    val status: HealthLevel,
    val lastSuccessNanos: Long? = null,
    val lastFailureNanos: Long? = null,
    val consecutiveFailures: Int = 0,
    val detailCode: String? = null
)
