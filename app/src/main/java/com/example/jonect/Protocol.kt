/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Marker type for UI JSON messages.
 */
@Serializable
sealed class ProtocolMessage

@Serializable
@SerialName("AndroidGetNativeSampleRate")
data class AndroidGetNativeSampleRate(
        val message_from: String,
): ProtocolMessage()

@Serializable
@SerialName("AndroidGetUsbAccessoryFileDescriptor")
object AndroidGetUsbAccessoryFileDescriptor: ProtocolMessage()

@Serializable
@SerialName("AndroidUsbAccessoryFileDescriptor")
data class AndroidUsbAccessoryFileDescriptor(
        val fd: String,
): ProtocolMessage()

@Serializable
@SerialName("AndroidNativeSampleRate")
data class AndroidNativeSampleRate(
        val native_sample_rate: Int,
        val frames_per_burst: Int,
        val message_to: String,
): ProtocolMessage()

@Serializable
@SerialName("ConnectTo")
data class ConnectTo(
        val ip_address: String,
): ProtocolMessage()

@Serializable
@SerialName("DeviceConnectionEstablished")
object DeviceConnectionEstablished: ProtocolMessage(), ILogicStatusEvent {
        override fun toString(): String {
                return "Connected"
        }
}

@Serializable
@SerialName("DeviceConnectionDisconnected")
object DeviceConnectionDisconnected: ProtocolMessage(), ILogicStatusEvent {
        override fun toString(): String {
                return "Disconnected"
        }
}

@Serializable
@SerialName("DeviceConnectionDisconnectedWithError")
object DeviceConnectionDisconnectedWithError: ProtocolMessage(), ILogicStatusEvent {
        override fun toString(): String {
                return "Connection error"
        }
}


@Serializable
@SerialName("DisconnectDevice")
object DisconnectDevice: ProtocolMessage()
