package hk.uwu.soundman.ipc

import android.media.AudioDeviceInfo
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredDeviceSyncTest {
    @Test
    fun prefsKeyUsesUid() {
        assertEquals("10123", PreferredDeviceSync.prefsKey(10123))
    }

    @Test
    fun encodeAndDecodePrefsRoundTripIncludingEmptyAddress() {
        val encoded = PreferredDeviceSync.encodePrefsValue(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
        assertEquals("2|", encoded)
        val spec = PreferredDeviceSync.decodePrefsValue(encoded)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, spec!!.publicType)
        assertEquals("", spec.address)
    }

    @Test
    fun decodePrefsTreatsBlankAsNoForcedDevice() {
        assertNull(PreferredDeviceSync.decodePrefsValue(null))
        assertNull(PreferredDeviceSync.decodePrefsValue(""))
        assertNull(PreferredDeviceSync.decodePrefsValue("   "))
        assertEquals("", PreferredDeviceSync.encodePrefsValue(PreferredDeviceSync.followSystem(10123)))
    }

    @Test
    fun decodePrefsRejectsMalformedValue() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PreferredDeviceSync.decodePrefsValue("not-a-route")
        }
        assertTrue(error.message.orEmpty().contains("publicType|address"))
    }

    @Test
    fun broadcastExtrasRoundTripForcedDevice() {
        val hint = PreferredDeviceSync.forced(10123, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:FF")
        val decoded = PreferredDeviceSync.decodeExtras(PreferredDeviceSync.extras(hint))
        assertEquals(hint, decoded)
        assertEquals(
            setOf(
                PreferredDeviceSync.EXTRA_UID,
                PreferredDeviceSync.EXTRA_FOLLOW_SYSTEM,
                PreferredDeviceSync.EXTRA_PUBLIC_TYPE,
                PreferredDeviceSync.EXTRA_ADDRESS,
                PreferredDeviceSync.EXTRA_USAGE,
            ),
            PreferredDeviceSync.extras(hint).keys,
        )
    }

    @Test
    fun broadcastExtrasRoundTripFollowSystem() {
        val hint = PreferredDeviceSync.followSystem(42)
        val decoded = PreferredDeviceSync.decodeExtras(PreferredDeviceSync.extras(hint))
        assertEquals(hint, decoded)
        assertTrue(decoded.followSystem)
        assertNull(decoded.spec)
    }

    @Test
    fun decodeExtrasRejectsMissingUid() {
        val error = assertThrows(IllegalStateException::class.java) {
            PreferredDeviceSync.decodeExtras(
                mapOf(
                    PreferredDeviceSync.EXTRA_FOLLOW_SYSTEM to true,
                    PreferredDeviceSync.EXTRA_PUBLIC_TYPE to 0,
                    PreferredDeviceSync.EXTRA_ADDRESS to "",
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains(PreferredDeviceSync.EXTRA_UID))
    }

    @Test
    fun matchesComparesPublicTypeAndAddress() {
        val spec = PreferredDeviceSync.DeviceSpec(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB")
        assertTrue(PreferredDeviceSync.matches(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB", spec))
        assertTrue(!PreferredDeviceSync.matches(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", spec))
        assertTrue(!PreferredDeviceSync.matches(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "", spec))
    }

    @Test
    fun resolvePublicDevicePrefersAddressThenProductNameThenType() {
        val speaker = PreferredDeviceSync.PublicDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Speaker")
        val a2dp = PreferredDeviceSync.PublicDevice(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            "AA:BB:CC:DD:EE:FF",
            "WH-1000XM5",
        )
        val sco = PreferredDeviceSync.PublicDevice(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            "AA:BB:CC:DD:EE:FF",
            "WH-1000XM5",
        )
        val devices = listOf(speaker, sco, a2dp)

        val byAddress = PreferredDeviceSync.resolvePublicDevice(
            devices,
            OutputTarget.Device(
                type = OutputDeviceType.BLUETOOTH,
                candidates = listOf(AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")),
                productName = "WH-1000XM5",
            ),
        )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, byAddress.publicType)
        assertEquals("AA:BB:CC:DD:EE:FF", byAddress.address)

        val byName = PreferredDeviceSync.resolvePublicDevice(
            devices,
            OutputTarget.Device(
                type = OutputDeviceType.BLUETOOTH,
                candidates = listOf(AudioDeviceIdentity(0x80, "")),
                productName = "WH-1000XM5",
            ),
        )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, byName.publicType)

        val byType = PreferredDeviceSync.resolvePublicDevice(
            devices,
            OutputTarget.Device(
                type = OutputDeviceType.BUILT_IN,
                candidates = listOf(AudioDeviceIdentity(2, "")),
                productName = "本机",
            ),
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, byType.publicType)
        assertEquals("", byType.address)
    }

    @Test
    fun resolvePublicDeviceFailsWhenNoMatch() {
        val error = assertThrows(IllegalStateException::class.java) {
            PreferredDeviceSync.resolvePublicDevice(
                devices = listOf(
                    PreferredDeviceSync.PublicDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Speaker"),
                ),
                target = OutputTarget.Device(
                    type = OutputDeviceType.BLUETOOTH,
                    candidates = listOf(AudioDeviceIdentity(0x80, "AA:BB:CC:DD:EE:FF")),
                    productName = "WH-1000XM5",
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("Failed to resolve public device"))
    }

    @Test
    fun hintForFollowSystemDoesNotNeedDevices() {
        val hint = PreferredDeviceSync.hintFor(emptyList(), 7, OutputTarget.FollowSystem)
        assertEquals(PreferredDeviceSync.followSystem(7), hint)
        assertEquals("", PreferredDeviceSync.encodePrefsValue(hint))
    }
}
