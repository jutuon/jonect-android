/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Marker type for protocol JSON messages.
 */
@Serializable
sealed class ProtocolMessage

/**
 * Server sends this message when client connects to it.
 */
@Serializable
@SerialName("ServerInfo")
data class ServerInfo(
        val version: String,
        val id: String,
        ): ProtocolMessage()

/**
 * When this is received then response with PingResponse message.
 * When this is sent then server should response with PingResponse.
 */
@Serializable
@SerialName("Ping")
object Ping: ProtocolMessage()

/**
 * Response message to Ping message.
 */
@Serializable
@SerialName("PingResponse")
object PingResponse: ProtocolMessage()

/**
 * Server sends this message when it request that client should play an audio stream.
 */
@Serializable
@SerialName("PlayAudioStream")
data class PlayAudioStream(
        val format: String,
        val channels: Short,
        val rate: Int,
        val port: Int,
): ProtocolMessage()

/**
 * Client should send this when TCP connection to the server is established.
 */
@Serializable
@SerialName("ClientInfo")
data class ClientInfo(
        val version: String,
        val id: String,
        val native_sample_rate: Int,
): ProtocolMessage()