//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier

object ImsNetworkRequestBuilder {
    fun buildForSubscription(subId: Int): NetworkRequest {
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            .setNetworkSpecifier(
                TelephonyNetworkSpecifier.Builder()
                    .setSubscriptionId(subId)
                    .build()
            )
            .build()
    }
}
