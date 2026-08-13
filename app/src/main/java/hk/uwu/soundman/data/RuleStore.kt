package hk.uwu.soundman.data

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget

const val RULE_PREFERENCES_NAME = "soundman_rules"
private const val TAG = "SoundManRuleStore"

/** 已持久化规则存在无法解析的数据时抛出，禁止静默改为跟随系统。 */
class CorruptedAudioRuleException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** 应用本地规则持久化边界；UID 是主键，包名仅用于展示与 UID 复用校验。 */
interface RuleStore {
    fun readAll(): Map<String, AppAudioRule>
    fun readOrDefault(packageName: String, uid: Int): AppAudioRule
    fun save(packageName: String, uid: Int, volumePercent: Int, outputTarget: OutputTarget): AppAudioRule
    fun updateVolume(packageName: String, uid: Int, volumePercent: Int): AppAudioRule
    fun fallbackToSystem(packageName: String, uid: Int, disconnectedTarget: OutputTarget.Device): AppAudioRule
    fun revision(): Long
}

class SharedPreferencesRuleStore(
    private val preferences: SharedPreferences,
) : RuleStore {
    override fun readAll(): Map<String, AppAudioRule> = preferences.getStringSet(KEY_UIDS, emptySet()).orEmpty()
        .map { encodedUid -> encodedUid.toIntOrNull() ?: corrupted("Invalid persisted UID key: $encodedUid") }
        .associate { uid ->
            val rule = readPersisted(uid)
            rule.packageName to rule
        }

    override fun readOrDefault(packageName: String, uid: Int): AppAudioRule {
        validateIdentity(packageName, uid)
        return if (preferences.getStringSet(KEY_UIDS, emptySet()).orEmpty().contains(uid.toString())) {
            val persisted = readPersisted(uid)
            if (persisted.packageName == packageName) persisted else AppAudioRule(
                packageName, uid, 100, OutputTarget.FollowSystem, 0L,
            )
        } else {
            AppAudioRule(packageName, uid, 100, OutputTarget.FollowSystem, 0L)
        }
    }

    override fun save(
        packageName: String,
        uid: Int,
        volumePercent: Int,
        outputTarget: OutputTarget,
    ): AppAudioRule {
        validateIdentity(packageName, uid)
        require(volumePercent in 0..100)
        return persist(AppAudioRule(packageName, uid, volumePercent, outputTarget, nextRevision()))
    }

    override fun updateVolume(packageName: String, uid: Int, volumePercent: Int): AppAudioRule {
        require(volumePercent in 0..100)
        val current = readOrDefault(packageName, uid)
        if (current.volumePercent == volumePercent) return current
        return persist(current.copy(packageName = packageName, uid = uid, volumePercent = volumePercent, revision = nextRevision()))
    }

    override fun fallbackToSystem(
        packageName: String,
        uid: Int,
        disconnectedTarget: OutputTarget.Device,
    ): AppAudioRule {
        val current = readOrDefault(packageName, uid)
        check(current.outputTarget == disconnectedTarget) { "Disconnected target no longer matches uid=$uid" }
        if (current.followsSystemAfterDisconnect) return current
        Log.w(TAG, "Fixed output disconnected; following system uid=$uid package=$packageName")
        return persist(current.copy(followsSystemAfterDisconnect = true, revision = nextRevision()))
    }

    override fun revision(): Long = preferences.getLong(KEY_REVISION, 0L).coerceAtLeast(0L)

    private fun persist(rule: AppAudioRule): AppAudioRule {
        val uids = preferences.getStringSet(KEY_UIDS, emptySet()).orEmpty().toMutableSet().apply { add(rule.uid.toString()) }
        val prefix = prefix(rule.uid)
        val committed = preferences.edit()
            .putStringSet(KEY_UIDS, uids)
            .putString(prefix + KEY_PACKAGE, rule.packageName)
            .putInt(prefix + KEY_VOLUME, rule.volumePercent)
            .putString(prefix + KEY_TARGET, encodeTarget(rule.outputTarget))
            .putBoolean(prefix + KEY_FALLBACK, rule.followsSystemAfterDisconnect)
            .putLong(prefix + KEY_RULE_REVISION, rule.revision)
            .putLong(KEY_REVISION, rule.revision)
            .commit()
        check(committed) { "Failed to persist audio rule uid=${rule.uid}" }
        return rule
    }

    private fun readPersisted(uid: Int): AppAudioRule {
        val prefix = prefix(uid)
        return try {
            AppAudioRule(
                packageName = preferences.getString(prefix + KEY_PACKAGE, null)
                    ?: corrupted("Persisted rule is missing package name for uid=$uid"),
                uid = uid,
                volumePercent = preferences.getInt(prefix + KEY_VOLUME, 100).coerceIn(0, 100),
                outputTarget = decodeTarget(preferences.getString(prefix + KEY_TARGET, null)),
                revision = preferences.getLong(prefix + KEY_RULE_REVISION, 0L).coerceAtLeast(0L),
                followsSystemAfterDisconnect = preferences.getBoolean(prefix + KEY_FALLBACK, false),
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to decode persisted rule uid=$uid", error)
            throw error
        }
    }

    private fun nextRevision(): Long {
        val current = revision()
        check(current < Long.MAX_VALUE) { "Audio rule revision overflow" }
        return current + 1L
    }

    private fun encodeTarget(target: OutputTarget): String = when (target) {
        OutputTarget.FollowSystem -> FOLLOW_SYSTEM
        is OutputTarget.Device -> listOf(
            DEVICE,
            target.type.name,
            encodeText(target.productName),
            target.candidates.joinToString(",") { candidate ->
                "${candidate.internalType}:${encodeText(candidate.address)}"
            },
        ).joinToString("|")
    }

    private fun decodeTarget(encoded: String?): OutputTarget {
        if (encoded == FOLLOW_SYSTEM) return OutputTarget.FollowSystem
        if (encoded.isNullOrBlank()) corrupted("Persisted audio rule has no output target")
        val parts = encoded!!.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != DEVICE) corrupted("Invalid output target encoding")
        val candidates = parts[3].split(',').filter(String::isNotBlank).map { encodedCandidate ->
            val separator = encodedCandidate.indexOf(':')
            if (separator <= 0) corrupted("Invalid route candidate encoding")
            AudioDeviceIdentity(
                internalType = encodedCandidate.substring(0, separator).toIntOrNull()
                    ?: corrupted("Invalid route candidate type"),
                address = decodeText(encodedCandidate.substring(separator + 1)),
            )
        }
        return OutputTarget.Device(
            type = OutputDeviceType.valueOf(parts[1]),
            candidates = candidates,
            productName = decodeText(parts[2]),
        )
    }

    private fun validateIdentity(packageName: String, uid: Int) {
        require(packageName.isNotBlank())
        require(uid >= 0)
    }

    private fun corrupted(message: String): Nothing = throw CorruptedAudioRuleException(message)

    private fun encodeText(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE,
    )

    private fun decodeText(value: String): String = String(
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8,
    )

    private fun prefix(uid: Int): String = "rule.uid.$uid."

    companion object {
        private const val KEY_UIDS = "uids"
        private const val KEY_REVISION = "revision"
        private const val KEY_PACKAGE = "package"
        private const val KEY_VOLUME = "volume"
        private const val KEY_TARGET = "target"
        private const val KEY_FALLBACK = "fallback_follow_system"
        private const val KEY_RULE_REVISION = "revision"
        private const val FOLLOW_SYSTEM = "follow"
        private const val DEVICE = "device"
    }
}
