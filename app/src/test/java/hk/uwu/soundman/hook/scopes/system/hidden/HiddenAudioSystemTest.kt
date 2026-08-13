package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeAudioSystem
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeAudioSystemMissingField
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakeAudioSystemMissingMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HiddenAudioSystemTest {
    private val classLoader: ClassLoader = requireNotNull(FakeAudioSystem::class.java.classLoader) {
        "FakeAudioSystem class loader is missing"
    }

    @Before
    fun resetFake() {
        FakeAudioSystem.reset()
    }

    @Test
    fun readsConstantsAndForwardsMethodArguments() {
        val audioSystem = create(FakeAudioSystem::class.java)
        FakeAudioSystem.connectionStateResult = 7
        FakeAudioSystem.setAffinityResult = 11
        FakeAudioSystem.removeAffinityResult = 13

        assertEquals(FakeAudioSystem.DEVICE_STATE_AVAILABLE, audioSystem.deviceStateAvailable)
        assertFalse(audioSystem.routesThroughAdapter)
        assertEquals(7, audioSystem.getDeviceConnectionState(32, "addr-a"))
        assertEquals(32, FakeAudioSystem.lastDevice)
        assertEquals("addr-a", FakeAudioSystem.lastAddress)

        val deviceIds = intArrayOf(2, 8)
        val addresses = arrayOf("speaker", "wired")
        assertEquals(11, audioSystem.setUidDeviceAffinities(1001, deviceIds, addresses))
        assertEquals(1001, FakeAudioSystem.lastUid)
        assertArrayEquals(deviceIds, FakeAudioSystem.lastDeviceIds)
        assertArrayEquals(addresses, FakeAudioSystem.lastDeviceAddresses)

        assertEquals(13, audioSystem.removeUidDeviceAffinities(2002))
        assertEquals(2002, FakeAudioSystem.lastUid)
    }

    @Test
    fun mapsPublicOutputTypesAndReturnsNullForUnknown() {
        val audioSystem = create(FakeAudioSystem::class.java)
        assertEquals(FakeAudioSystem.DEVICE_OUT_EARPIECE, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(FakeAudioSystem.DEVICE_OUT_SPEAKER, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(FakeAudioSystem.DEVICE_OUT_WIRED_HEADSET, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(FakeAudioSystem.DEVICE_OUT_WIRED_HEADPHONE, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(FakeAudioSystem.DEVICE_OUT_BLUETOOTH_SCO, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(FakeAudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(FakeAudioSystem.DEVICE_OUT_USB_ACCESSORY, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(FakeAudioSystem.DEVICE_OUT_USB_DEVICE, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(FakeAudioSystem.DEVICE_OUT_USB_HEADSET, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(FakeAudioSystem.DEVICE_OUT_BLE_HEADSET, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(FakeAudioSystem.DEVICE_OUT_BLE_SPEAKER, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(FakeAudioSystem.DEVICE_OUT_BLE_BROADCAST, audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertNull(audioSystem.outputDeviceType(AudioDeviceInfo.TYPE_UNKNOWN))
        assertNull(audioSystem.outputDeviceType(999))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audioSystem.publicOutputType(FakeAudioSystem.DEVICE_OUT_SPEAKER))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, audioSystem.publicOutputType(FakeAudioSystem.DEVICE_OUT_BLUETOOTH_A2DP))
        assertNull(audioSystem.publicOutputType(0x123456))
    }

    @Test
    fun constructorFailsWhenClassIsMissing() {
        val className = "hk.uwu.soundman.hook.scopes.system.hidden.fakes.DoesNotExist"
        val error = assertThrows(IllegalStateException::class.java) {
            HiddenAudioSystem(classLoader, className)
        }
        assertTrue(error.message.orEmpty().contains(className))
    }

    @Test
    fun constructorFailsWhenDeviceOutFieldIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            create(FakeAudioSystemMissingField::class.java)
        }
        assertTrue(error.message.orEmpty().contains("DEVICE_OUT_SPEAKER"))
        assertTrue(error.message.orEmpty().contains(FakeAudioSystemMissingField::class.java.name))
    }

    @Test
    fun constructorFailsWhenMethodIsMissing() {
        val error = assertThrows(IllegalStateException::class.java) {
            create(FakeAudioSystemMissingMethod::class.java)
        }
        assertTrue(error.message.orEmpty().contains("getDeviceConnectionState"))
        assertTrue(error.message.orEmpty().contains(FakeAudioSystemMissingMethod::class.java.name))
    }

    @Test
    fun unwrapsInvocationTargetExceptionFromMethods() {
        val audioSystem = create(FakeAudioSystem::class.java)
        val getError = IllegalArgumentException("connection boom")
        val setError = IllegalStateException("affinity boom")
        val removeError = UnsupportedOperationException("remove boom")
        FakeAudioSystem.throwOnGet = getError
        FakeAudioSystem.throwOnSet = setError
        FakeAudioSystem.throwOnRemove = removeError

        assertSame(getError, assertThrows(IllegalArgumentException::class.java) {
            audioSystem.getDeviceConnectionState(1, "x")
        })
        assertSame(setError, assertThrows(IllegalStateException::class.java) {
            audioSystem.setUidDeviceAffinities(1, intArrayOf(1), arrayOf("x"))
        })
        assertSame(removeError, assertThrows(UnsupportedOperationException::class.java) {
            audioSystem.removeUidDeviceAffinities(1)
        })
    }

    private fun create(fakeClass: Class<*>): HiddenAudioSystem =
        HiddenAudioSystem(classLoader, fakeClass.name)
}
