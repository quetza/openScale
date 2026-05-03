/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Handler for the eufy Smart Scale A1 (model T9120).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS HANDLER EXISTS
 * ─────────────────────────────────────────────────────────────────────────────
 * The T9120 advertises service UUID 0xFFF0, which is also used by InlifeHandler.
 * InlifeHandler tries to subscribe to FFF1 for notifications, but on the T9120
 * that characteristic has no CCC descriptor — causing a fatal "could not get CCC
 * descriptor" error and no data received.
 *
 * This handler is registered before InlifeHandler in ScaleFactory and matches
 * only on the name prefix "eufy T9120", so InlifeHandler continues to own all
 * other FFF0-service devices it previously handled.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GATT CHARACTERISTICS
 * ─────────────────────────────────────────────────────────────────────────────
 *   Service:  0000FFF0-0000-1000-8000-00805f9b34fb
 *   Notify:   0000FFF4-0000-1000-8000-00805f9b34fb  <- correct for T9120
 *   Write:    0000FFF1-0000-1000-8000-00805f9b34fb  <- correct for T9120
 *
 * Source: https://github.com/bdr99/eufylife-ble-client (devices.py / client.py)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WIRE PROTOCOL — CONFIRMED FACTS
 * ─────────────────────────────────────────────────────────────────────────────
 * All frames are 11 bytes. Evidence:
 *   - Every notification observed on FFF4 was 11 bytes.
 *   - eufylife-ble-client _handle_weight_update_t9120() checks len(data)==11.
 *
 * Frame layout:
 *   Byte  0     : 0xCF — frame type marker (always)
 *   Bytes 1-2   : impedance, little-endian uint16, units of 0.1 ohm
 *                 Zero in live-weight frames; non-zero in result frames.
 *   Bytes 3-4   : weight, little-endian uint16, units of 0.01 kg
 *                 i.e. weight_kg = ((data[4] << 8) | data[3]) / 100.0
 *   Bytes 5-8   : unknown / reserved (observed as 0x00 in most frames)
 *   Byte  9     : status
 *                   0x01 = live/unstable weight (stepping on)
 *                   0x00 = stable/final weight  -> publish measurement
 *                   0x02 = weight limit exceeded
 *   Byte  10    : XOR checksum of bytes [0..9]
 *
 * Verified:
 *   - Weight encoding confirmed: observed 83.80 kg matched scale display.
 *     Source: eufylife-ble-client _handle_weight_update_t9120 uses identical formula.
 *   - Checksum: XOR of bytes [0..9] == byte [10] verified on all observed frames.
 *   - Status byte 9: 0x01 during stepping-on sequence, 0x00 on stable frames.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WIRE PROTOCOL — ASSUMPTIONS (NOT YET VERIFIED AGAINST OFFICIAL APP OUTPUT)
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. IMPEDANCE FIELD (bytes 1-2):
 *    Assumed to be little-endian uint16 in units of 0.1 ohm.
 *    Basis: observed values 4000 and 4070 in result frames -> 400.0 and 407.0 ohm,
 *    which are plausible two-leg BIA values for an adult male at ~84 kg.
 *    The T9146 (same FFF0/FFF4/FFF1 service/characteristics) sends a stable-weight
 *    broadcast containing "weight,impedance,..." as a comma-separated string parsed
 *    by T9146CmdDispatcher; the impedance field position and the 0.1 ohm unit are
 *    inferred by analogy with that model and cross-checked against the byte values.
 *    RISK: If the unit is 1 ohm (not 0.1 ohm), the raw values 4000/4070 are far
 *    too high for BIA and the body-composition results will be wrong.
 *
 * 2. BODY COMPOSITION FORMULAS:
 *    The official EufyLife app delegates all BIA calculations to the Holtek
 *    libHTBodyfat native library (identified by APK decompilation). That library
 *    is closed-source. The formulas used here are open BIA approximations sourced
 *    from ble-scale-sync (KristianP26/ble-scale-sync, body-comp-helpers.ts) and
 *    are NOT identical to the Holtek library. Results will be in the right
 *    ballpark but may differ from the official app by a few percent.
 *    RISK: Systematic offset vs. official app is expected and acceptable for
 *    trend-tracking; absolute accuracy is not guaranteed.
 *
 * 3. RESULT-FRAME TRIGGER:
 *    A result frame is identified by status byte [9] == 0x00 AND bytes [1:3] != 0.
 *    Basis: all stable/final frames observed had byte [9] == 0x00; the two frames
 *    that also had non-zero bytes [1:3] appeared after weight stabilised,
 *    consistent with a final measurement including impedance.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FURTHER READING
 * ─────────────────────────────────────────────────────────────────────────────
 *   https://github.com/bdr99/eufylife-ble-client
 *   https://github.com/KristianP26/ble-scale-sync (body-comp-helpers.ts)
 *   EufyLife APK decompilation: com.oceanwing.eufylife /
 *     diapatcher/T9146CmdDispatcher, com.belter.fat.ScaleSDKManager,
 *     com.holtek.libHTBodyfat.HTBodyResultTwoLegs
 */
class EufyHandler : ScaleDeviceHandler() {

    // ── GATT UUIDs ────────────────────────────────────────────────────────────
    private val SVC        : UUID = uuid16(0xFFF0)
    private val CHR_NOTIFY : UUID = uuid16(0xFFF4)
    private val CHR_CMD    : UUID = uuid16(0xFFF1)

    // ── Frame constants ───────────────────────────────────────────────────────
    private val FRAME_LEN         = 11
    private val MARKER     : Byte = 0xCF.toByte()
    private val STATUS_LIVE       = 0x01
    private val STATUS_STABLE     = 0x00
    private val STATUS_OVERWEIGHT = 0x02

    // ── Device matching ───────────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = (device.name ?: "").lowercase(Locale.ROOT)
        if (!name.startsWith("eufy t9120")) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        )
        return DeviceSupport(
            displayName = "eufy Smart Scale A1 (T9120)",
            capabilities = caps,
            implemented  = caps,
            linkMode     = LinkMode.CONNECT_GATT
        )
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(SVC, CHR_NOTIFY)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY) return

        if (data.size != FRAME_LEN) {
            logW("Unexpected frame length ${data.size} (expected $FRAME_LEN) — ignored")
            return
        }
        if (data[0] != MARKER) {
            logW("Unexpected frame marker 0x%02X (expected 0xCF) — ignored".format(data[0]))
            return
        }

        // Verify XOR checksum: XOR of bytes [0..9] must equal byte [10]
        var xor = 0
        for (i in 0..9) xor = xor xor (data[i].toInt() and 0xFF)
        if ((xor and 0xFF).toByte() != data[10]) {
            logW("XOR checksum mismatch — frame discarded")
            return
        }

        val weightKg  = u16Le(data, 3) / 100.0f
        val status    = data[9].toInt() and 0xFF
        val impedance = u16Le(data, 1)  // 0 in live frames; assumed non-zero in result frames

        when (status) {
            STATUS_OVERWEIGHT -> {
                logW("Weight limit exceeded")
            }

            STATUS_LIVE -> {
                logD("Live weight: %.2f kg".format(weightKg))
                userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            }

            STATUS_STABLE -> {
                if (impedance == 0) {
                    // Stable frame with no impedance — weight only.
                    // Occurs if feet were not correctly placed for BIA measurement.
                    logD("Stable weight only (no impedance): %.2f kg".format(weightKg))
                    publish(ScaleMeasurement().apply { weight = weightKg })
                } else {
                    // Result frame: weight + impedance -> full body composition.
                    val impedanceOhm = impedance / 10.0f  // ASSUMPTION: units of 0.1 ohm
                    logD("Result: weight=%.2f kg, impedance=%.1f ohm".format(weightKg, impedanceOhm))
                    publishWithBodyComp(weightKg, impedanceOhm, user)
                }
            }

            else -> logD("Unknown status 0x%02X — ignored".format(status))
        }
    }

    // ── Body composition ──────────────────────────────────────────────────────

    /**
     * Calculates and publishes body composition from weight and impedance.
     *
     * Uses open BIA formulas from ble-scale-sync (body-comp-helpers.ts).
     * Results will differ somewhat from the official app (Holtek libHTBodyfat).
     * See the ASSUMPTIONS section in the class documentation.
     */
    private fun publishWithBodyComp(weightKg: Float, impedanceOhm: Float, user: ScaleUser) {
        val heightCm = user.bodyHeight.toDouble()
        val heightM  = heightCm / 100.0
        val age      = user.age.toDouble()
        val male     = user.gender.isMale()
        val athlete  = athleteLevel(user) > 0
        val w        = weightKg.toDouble()
        val z        = impedanceOhm.toDouble()

        // BIA lean body mass — coefficients from ble-scale-sync body-comp-helpers.ts
        val c1: Double; val c2: Double; val c3: Double; val c4: Double
        if (male && athlete)      { c1=0.637; c2=0.205; c3=-0.18;  c4=12.5  }
        else if (male)            { c1=0.503; c2=0.165; c3=-0.158; c4=17.8  }
        else if (athlete)         { c1=0.55;  c2=0.18;  c3=-0.15;  c4=8.5   }
        else                      { c1=0.49;  c2=0.15;  c3=-0.13;  c4=11.5  }

        val h2r = (heightCm * heightCm) / z
        var lbm = c1 * h2r + c2 * w + c3 * age + c4
        if (lbm > w) lbm = w * 0.96

        val fatKg      = w - lbm
        val fatPct     = (fatKg / w) * 100.0
        val waterPct   = (lbm * (if (athlete) 0.74 else 0.73) / w) * 100.0
        val musclePct  = (lbm * (if (athlete) 0.60 else 0.54) / w) * 100.0
        val boneMass   = lbm * 0.042
        val bmi        = w / (heightM * heightM)
        val visceralFat = if (fatPct > 10.0)
            clamp(fatPct * 0.55 - 4.0 + age * 0.08, 1.0, 59.0) else 1.0

        logD(
            "Body comp: fat=%.1f%% water=%.1f%% muscle=%.1f%% bone=%.2fkg " +
            "BMI=%.1f visceral=%.0f".format(fatPct, waterPct, musclePct, boneMass, bmi, visceralFat)
        )

        publish(ScaleMeasurement().apply {
            weight      = weightKg
            fat         = clamp(fatPct,    3.0,  75.0).toFloat()
            water       = clamp(waterPct,  5.0,  80.0).toFloat()
            muscle      = clamp(musclePct, 5.0,  90.0).toFloat()
            bone        = clamp(boneMass,  0.3,   8.0).toFloat()
            lbm         = lbm.toFloat()
            visceralFat = visceralFat.toFloat()
            impedance   = impedanceOhm.toDouble()
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Little-endian unsigned 16-bit integer from bytes [off] and [off+1]. */
    private fun u16Le(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun athleteLevel(u: ScaleUser): Int = when (u.activityLevel) {
        ActivityLevel.SEDENTARY, ActivityLevel.MILD -> 0
        ActivityLevel.MODERATE                      -> 1
        ActivityLevel.HEAVY, ActivityLevel.EXTREME  -> 2
        else                                        -> 0
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
}
