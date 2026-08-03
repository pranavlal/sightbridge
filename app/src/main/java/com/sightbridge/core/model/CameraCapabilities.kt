package com.sightbridge.core.model

data class CameraCapabilities(
    val supportedResolutions: List<Pair<Int, Int>>,
    val supportedFrameRates: List<Int>,
    val supportsHardwareAcceleration: Boolean,
    val isSimulated: Boolean,
    val maxRotationDegrees: Int = 360
)
