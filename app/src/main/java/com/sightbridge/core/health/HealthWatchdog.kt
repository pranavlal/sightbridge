package com.sightbridge.core.health

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Active System-Wide Health Watchdog per Section 27 of specification.
 * Monitors component heartbeats, staleness, and automatic degraded/recovery state transitions.
 */
class HealthWatchdog(
    private val frameTimeoutMs: Long = 2000
) {
    private val lock = ReentrantLock()
    private val healthMap = ConcurrentHashMap<String, ComponentHealth>()
    private val _systemHealth = MutableStateFlow<Map<String, ComponentHealth>>(emptyMap())
    val systemHealth: StateFlow<Map<String, ComponentHealth>> = _systemHealth.asStateFlow()

    private var watchdogJob: Job? = null

    fun updateHealth(
        component: String,
        status: HealthLevel,
        detailCode: String? = null
    ) {
        lock.withLock {
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
            _systemHealth.value = HashMap(healthMap)
        }
    }

    /**
     * Record a fresh frame/heartbeat arrival for a component.
     * Automatically recovers DEGRADED state to HEALTHY.
     */
    fun recordHeartbeat(component: String, detailCode: String? = null) {
        lock.withLock {
            val currentNanos = System.nanoTime()
            val prev = healthMap[component]
            val updated = ComponentHealth(
                component = component,
                status = HealthLevel.HEALTHY,
                lastSuccessNanos = currentNanos,
                lastFailureNanos = prev?.lastFailureNanos,
                consecutiveFailures = 0,
                detailCode = detailCode ?: prev?.detailCode ?: "Active"
            )
            healthMap[component] = updated
            _systemHealth.value = HashMap(healthMap)
        }
    }

    /**
     * Starts an active watchdog coroutine loop monitoring component staleness.
     */
    fun startWatchdog(scope: CoroutineScope) {
        if (watchdogJob != null) return
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                checkStaleness()
                delay(1000)
            }
        }
    }

    fun checkStaleness(currentMonotonicNanos: Long = System.nanoTime()) {
        lock.withLock {
            var updated = false
            for ((comp, health) in healthMap) {
                if (health.status == HealthLevel.HEALTHY && health.lastSuccessNanos != null) {
                    val ageMs = (currentMonotonicNanos - health.lastSuccessNanos) / 1_000_000
                    if (ageMs > frameTimeoutMs) {
                        healthMap[comp] = health.copy(
                            status = HealthLevel.DEGRADED,
                            detailCode = "Stale: No heartbeat for ${ageMs}ms"
                        )
                        updated = true
                    }
                }
            }
            if (updated) {
                _systemHealth.value = HashMap(healthMap)
            }
        }
    }

    fun getHealth(component: String): ComponentHealth? {
        return healthMap[component]
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
