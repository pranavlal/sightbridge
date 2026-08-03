package com.sightbridge.core.model

enum class CameraSourceState {
    UNINITIALISED,
    INITIALISING,
    PERMISSION_REQUIRED,
    READY,
    CONNECTING,
    CONNECTED,
    STARTING_STREAM,
    STREAMING,
    DEGRADED,
    RECONNECTING,
    STOPPING,
    DISCONNECTED,
    FAILED,
    RELEASED
}
