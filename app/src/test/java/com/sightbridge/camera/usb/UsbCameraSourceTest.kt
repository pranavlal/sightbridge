package com.sightbridge.camera.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class UsbCameraSourceTest {
    private fun device(vararg classes: Pair<Int, Int>): UsbDevice {
        val device = mock(UsbDevice::class.java)
        `when`(device.interfaceCount).thenReturn(classes.size)
        classes.forEachIndexed { index, (deviceClass, subclass) ->
            val usbInterface = mock(UsbInterface::class.java)
            `when`(usbInterface.interfaceClass).thenReturn(deviceClass)
            `when`(usbInterface.interfaceSubclass).thenReturn(subclass)
            `when`(device.getInterface(index)).thenReturn(usbInterface)
        }
        return device
    }
    @Test fun detectsVideoControlInCompositeDevice() {
        assertTrue(UsbCameraSource.isUvcDevice(device(1 to 1, 14 to 1, 14 to 2)))
    }
    @Test fun ignoresNonCameraUsbDevices() {
        assertFalse(UsbCameraSource.isUvcDevice(device(8 to 6)))
        assertFalse(UsbCameraSource.isUvcDevice(device(1 to 1)))
        assertFalse(UsbCameraSource.isUvcDevice(device()))
    }
    @Test fun requiresVideoControlInterface() {
        assertFalse(UsbCameraSource.isUvcDevice(device(14 to 2)))
    }
}
