package com.sightbridge.core.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * System-wide Health Watchdog tracking component statuses per Section 27.
 */
class HealthWatchdog {

    private val healthMap = ConcurrentHashMap<String, ComponentHealth>()
    private val _systemHealth = MutableStateFlow<Map<String, ComponentHealth>>(emptyMap())
    val systemHealth: StateFlow<Map<String, ComponentHealth>> = _systemHealth.asStateFlow()

    fun updateHealth(
        component: String,
        status: HealthLevel,
        detailCode: String? = null
    ) {
        val currentNanos = System.nanoTime()
        val prev = healthMap[component]
        val consecFailures = if (status == HealthLevel.UNAVAILABLE || status == HealthLevel.DEGRADED) {
            (prev?.consecutiveFailures ?: 0) + 1
        } else {
            0
        }

        val updated = ComponentHealth(
            component = component,
            status = status,
            lastSuccessNanos = if (status == HealthLevel.HEALTHY) currentNanos else prev?.lastSuccessNanos,
            lastFailureNanos = if (status != HealthLevel.HEALTHY) currentNanos else prev?.lastFailureNanos,
            consecutiveFailures = consecFailures,
            detailCode = detailCode
        )

        healthMap[component] = updated
        _systemHealth.value = healthMap.toMap()
    }

    fun getHealth(component: String): ComponentHealth? {
        return healthMap[component]
    }
}
