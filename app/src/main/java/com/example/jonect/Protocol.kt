package com.example.jonect

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(val version: String, val id: String)

@Serializable
sealed class ServerMessage {
    data class ServerInfo(val serverInfo: ServerInfo)
}
