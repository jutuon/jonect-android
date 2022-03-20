/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */


package com.example.jonect

import android.hardware.usb.UsbManager

class UsbLogic(private val usbManager: UsbManager) {
    fun connectAccessory(): Int?  {
        val array = this.usbManager.accessoryList ?: return null

        for (accessory in array.iterator()) {
            if (accessory != null && accessory.model == "Jonect") {
                if (!this.usbManager.hasPermission(accessory)) {
                    println("Error: no USB accessory permission.")
                    return null
                } else {
                    val parcelFileDescriptor = this.usbManager.openAccessory(accessory)
                    if (parcelFileDescriptor != null) {
                        if (parcelFileDescriptor.statSize == -1L) {
                            println("ParcelFileDescriptor is not a file.")
                        }
                        val fd = parcelFileDescriptor.fd
                        parcelFileDescriptor.detachFd()
                        return fd
                    }
                }
            }
        }

        return null
    }

}