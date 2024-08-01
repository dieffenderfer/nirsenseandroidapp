package com.dieff.aurelian.foregroundService.ble

import java.time.Instant

/**
 * Base class representing a data packet from a NIRSense device.
 * This abstract class defines the common properties for all types of packets.
 *
 * @property deviceMacAddress The MAC address of the device sending the packet, represented as a Long.
 * @property captureTime The timestamp when the packet was captured.
 * @property sessionId A unique identifier for the current data collection session.
 */
open class Packet(
    open val deviceMacAddress: Long,
    open var captureTime: Instant,
    open val sessionId: UByte,
)

/**
 * Represents a data packet from an Argus device.
 */
data class ArgusPacket(
    override val deviceMacAddress: Long,
    override var captureTime: Instant,
    val argusVersion: Int,
    val mm660_8: Short,
    val mm660_30: Short,
    val mm660_35: Short,
    val mm660_40: Short,
    val mm735_8: Short,
    val mm735_30: Short,
    val mm735_35: Short,
    val mm735_40: Short,
    val mm810_8: Short,
    val mm810_30: Short,
    val mm810_35: Short,
    val mm810_40: Short,
    val mm850_8: Short,
    val mm850_30: Short,
    val mm850_35: Short,
    val mm850_40: Short,
    val mm890_8: Short,
    val mm890_30: Short,
    val mm890_35: Short,
    val mm890_40: Short,
    val mmAmbient_8: Short,
    val mmAmbient_30: Short,
    val mmAmbient_35: Short,
    val mmAmbient_40: Short,
    val temperature: Float,
    val accelerometerX: Short,
    val accelerometerY: Short,
    val accelerometerZ: Short,
    val timer: UInt,
    val sequenceCounter: UShort,
    val eventBit: Boolean,
    val hbO2: Float,
    val hbd: Float,
    override val sessionId: UByte,
    val pulseRate: UByte,
    val respiratoryRate: UByte,
    val spO2: UByte,
    val ppgWaveform: Short,
    val stO2: UByte,
    val reserved8: UByte,
    val reserved16: UShort,
    val reserved32: UInt
) : Packet(deviceMacAddress, captureTime, sessionId)

/**
 * Represents a data packet from an Aurelian EEG device.
 */
data class AurelianPacket(
    override val deviceMacAddress: Long,
    override var captureTime: Instant,
    val eegC1: Int,
    val eegC2: Int,
    val eegC3: Int,
    val eegC4: Int,
    val eegC5: Int,
    val eegC6: Int,
    val accelerometerX: Short,
    val accelerometerY: Short,
    val accelerometerZ: Short,
    val timeElapsed: Double,
    val counter: Int,
    val marker: Boolean,
    override val sessionId: UByte,
    val pulseRate: UByte,
    val tdcsImpedance: UShort,
    val tdcsCurrent: UShort,
    val tdcsOnTime: UShort,
    val batteryRSOC: UByte,
    val reserved8: UByte,
    val reserved64: ULong
) : Packet(deviceMacAddress, captureTime, sessionId)