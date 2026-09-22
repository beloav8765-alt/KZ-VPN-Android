package com.kzvpn.app

import android.app.Application
import com.wireguard.android.backend.GoBackend

class KzVpnApp : Application() {
    lateinit var vpnController: VpnController
        private set

    override fun onCreate() {
        super.onCreate()
        vpnController = VpnController(this)
        GoBackend.setAlwaysOnCallback {
            vpnController.connectFromAlwaysOn()
        }
    }
}
