// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asks the system about the helper's own situation (uid, permissions, audio state) and gives the
 * answers to [EnvReport]. It reads only the helper's own state: no call, number or name is touched.
 *
 * Call it inside [asSelf] when it is reached from an AIDL method.
 */
internal class EnvCollector(
    private val context: Context,
    private val shell: ShellContext?,
) {
    fun collect(meterLine: String?): String = EnvReport.format(snapshot(meterLine))

    fun snapshot(meterLine: String?): EnvSnapshot {
        val audio = attempt { context.getSystemService(AudioManager::class.java) }
        return EnvSnapshot(
            timeText = ZonedDateTime.now().format(TIME_FORMAT),
            helperPid = Process.myPid(),
            helperUid = Process.myUid(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildDisplay = Build.DISPLAY,
            permissions = EnvReport.PERMISSION_NAMES.map { name ->
                PermissionCheck(
                    name = name,
                    granted = attempt {
                        context.checkSelfPermission("android.permission.$name") == PackageManager.PERMISSION_GRANTED
                    },
                )
            },
            audioMode = audio?.let { attempt { it.mode } },
            microphoneMuted = audio?.let { attempt { it.isMicrophoneMute } },
            communicationDeviceType = audio?.let { attempt { it.communicationDevice?.type } },
            outputTypes = audio?.let { attempt { deviceTypes(it, AudioManager.GET_DEVICES_OUTPUTS) } },
            inputTypes = audio?.let { attempt { deviceTypes(it, AudioManager.GET_DEVICES_INPUTS) } },
            pstnInterceptable = audio?.let(::pstnInterceptable) ?: "AudioManager が取れなかった",
            attributionPackage = shell?.attributionPackage,
            attributionUid = shell?.attributionUid,
            meterLine = meterLine,
        )
    }

    // Only the types are listed. Product names and addresses (a Bluetooth device's name, for one)
    // could identify a person, so they are never read.
    private fun deviceTypes(audio: AudioManager, flags: Int): List<Int> =
        audio.getDevices(flags).map { it.type }

    /**
     * AudioManager.isPstnCallAudioInterceptable() is a system API that the SDK does not list, so it
     * is called by reflection. The answer is only a text for the report.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun pstnInterceptable(audio: AudioManager): String = try {
        audio.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(audio)?.toString() ?: "null"
    } catch (_: NoSuchMethodException) {
        "この版にはない"
    } catch (e: InvocationTargetException) {
        "取得できなかった（${e.targetException.javaClass.simpleName}）"
    } catch (e: Throwable) {
        "取得できなかった（${e.javaClass.simpleName}）"
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'xxx", Locale.ROOT)
    }
}
