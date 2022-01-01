/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ProtocolMessage

@Serializable
@SerialName("ServerInfo")
data class ServerInfo(
        val version: String,
        val id: String,
        ): ProtocolMessage()

@Serializable
@SerialName("Ping")
object Ping: ProtocolMessage()

@Serializable
@SerialName("PingResponse")
object PingResponse: ProtocolMessage()

@Serializable
@SerialName("PlayAudioStream")
data class PlayAudioStream(
        val format: String,
        val channels: Short,
        val rate: Int,
        val port: Int,
): ProtocolMessage()

@Serializable
@SerialName("ClientInfo")
data class ClientInfo(
        val version: String,
        val id: String,
        val native_sample_rate: Int,
): ProtocolMessage()