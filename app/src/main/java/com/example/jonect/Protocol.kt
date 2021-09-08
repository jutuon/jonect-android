/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.example.jonect

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(val version: String, val id: String)

@Serializable
sealed class ServerMessage {
    data class ServerInfo(val serverInfo: ServerInfo)
}
