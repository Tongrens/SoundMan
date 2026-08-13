package hk.uwu.soundman.ui

import hk.uwu.soundman.data.AudioDeviceScan
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePageRowsTest {
    private val rows = DevicePageRows()

    @Test
    fun followSystemSelectionMarksFollowSystemNotBuiltin() {
        val builtin = builtinDevice()
        val bluetooth = bluetoothDevice()
        val built = rows.build(
            scan = AudioDeviceScan(listOf(builtin, bluetooth), null),
            rule = followSystemRule(),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertEquals(3, built.size)
        assertEquals(DevicePageRowKind.FOLLOW_SYSTEM, built[0].kind)
        assertEquals(FOLLOW, built[0].name)
        assertTrue(built[0].selected)
        assertEquals(OutputTarget.FollowSystem, built[0].clickTarget)
        assertNull(built[0].type)

        val builtinRow = built[1]
        assertEquals(DevicePageRowKind.DEVICE, builtinRow.kind)
        assertEquals(BUILTIN, builtinRow.name)
        assertEquals(OutputDeviceType.BUILT_IN, builtinRow.type)
        assertFalse(builtinRow.selected)
        assertEquals(builtin.target, builtinRow.clickTarget)

        val bluetoothRow = built[2]
        assertEquals(DevicePageRowKind.DEVICE, bluetoothRow.kind)
        assertEquals(bluetooth.productName, bluetoothRow.name)
        assertFalse(bluetoothRow.selected)
    }

    @Test
    fun builtinAndBluetoothBothPresentAndBuiltinClickIsDevice() {
        val builtin = builtinDevice()
        val bluetooth = bluetoothDevice()
        val built = rows.build(
            scan = AudioDeviceScan(listOf(builtin, bluetooth), null),
            rule = followSystemRule(),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertEquals(listOf(OutputDeviceType.BUILT_IN, OutputDeviceType.BLUETOOTH), built.mapNotNull { it.type })
        val builtinRow = built.single { it.kind == DevicePageRowKind.DEVICE && it.type == OutputDeviceType.BUILT_IN }
        val target = builtinRow.clickTarget as OutputTarget.Device
        assertEquals(OutputDeviceType.BUILT_IN, target.type)
        assertEquals(builtin.target, target)
        assertTrue(target.candidates.any { it.address.isEmpty() })
    }

    @Test
    fun selectingBuiltinMarksBuiltinNotFollowSystem() {
        val builtin = builtinDevice()
        val bluetooth = bluetoothDevice()
        val built = rows.build(
            scan = AudioDeviceScan(listOf(builtin, bluetooth), null),
            rule = AppAudioRule(PACKAGE, UID, 100, builtin.target, 1L),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertFalse(built[0].selected)
        assertEquals(DevicePageRowKind.FOLLOW_SYSTEM, built[0].kind)
        assertTrue(built[1].selected)
        assertEquals(DevicePageRowKind.DEVICE, built[1].kind)
        assertEquals(OutputDeviceType.BUILT_IN, built[1].type)
        assertEquals(builtin.target, built[1].clickTarget)
        assertFalse(built[2].selected)
    }

    @Test
    fun disconnectedPeripheralIsDisabledWithoutClickTarget() {
        val builtin = builtinDevice()
        val missing = OutputTarget.Device(
            type = OutputDeviceType.BLUETOOTH,
            candidates = listOf(AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")),
            productName = "WH-1000XM",
        )
        val built = rows.build(
            scan = AudioDeviceScan(listOf(builtin), null),
            rule = AppAudioRule(PACKAGE, UID, 80, missing, 2L, followsSystemAfterDisconnect = true),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertTrue(built[0].selected)
        assertEquals(DevicePageRowKind.FOLLOW_SYSTEM, built[0].kind)
        val disconnected = built.single { it.kind == DevicePageRowKind.DISCONNECTED }
        assertEquals("WH-1000XM", disconnected.name)
        assertEquals(OutputDeviceType.BLUETOOTH, disconnected.type)
        assertFalse(disconnected.enabled)
        assertFalse(disconnected.selected)
        assertNull(disconnected.clickTarget)
    }

    @Test
    fun doesNotFabricateBuiltinWhenScanHasNone() {
        val bluetooth = bluetoothDevice()
        val built = rows.build(
            scan = AudioDeviceScan(listOf(bluetooth), null),
            rule = followSystemRule(),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertEquals(DevicePageRowKind.FOLLOW_SYSTEM, built[0].kind)
        assertTrue(built[0].selected)
        assertTrue(built.none { it.type == OutputDeviceType.BUILT_IN })
        assertEquals(listOf(DevicePageRowKind.FOLLOW_SYSTEM, DevicePageRowKind.DEVICE), built.map { it.kind })
        assertEquals(bluetooth.target, built[1].clickTarget)
    }

    @Test
    fun connectedBoundPeripheralIsSelectedAndNotDuplicatedAsDisconnected() {
        val builtin = builtinDevice()
        val bluetooth = bluetoothDevice()
        val built = rows.build(
            scan = AudioDeviceScan(listOf(builtin, bluetooth), null),
            rule = AppAudioRule(PACKAGE, UID, 100, bluetooth.target, 3L),
            followSystemName = FOLLOW,
            builtinName = BUILTIN,
        )

        assertFalse(built[0].selected)
        assertFalse(built[1].selected)
        assertTrue(built[2].selected)
        assertTrue(built.none { it.kind == DevicePageRowKind.DISCONNECTED })
    }

    private fun followSystemRule(): AppAudioRule =
        AppAudioRule(PACKAGE, UID, 100, OutputTarget.FollowSystem, 0L)

    private fun builtinDevice(): AudioOutputDevice = AudioOutputDevice(
        type = OutputDeviceType.BUILT_IN,
        candidates = listOf(
            AudioDeviceIdentity(0x2, ""),
            AudioDeviceIdentity(0x1, ""),
        ),
        productName = "Speaker",
    )

    private fun bluetoothDevice(): AudioOutputDevice = AudioOutputDevice(
        type = OutputDeviceType.BLUETOOTH,
        candidates = listOf(
            AudioDeviceIdentity(0x80, "11:22:33:44:55:66"),
            AudioDeviceIdentity(0x2000000, "11:22:33:44:55:66"),
        ),
        productName = "Pixel Buds",
    )

    private companion object {
        const val FOLLOW = "跟随系统"
        const val BUILTIN = "本机"
        const val PACKAGE = "com.example.player"
        const val UID = 10123
    }
}
