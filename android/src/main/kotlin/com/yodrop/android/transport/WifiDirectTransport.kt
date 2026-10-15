package com.yodrop.android.transport

import android.content.Context
import com.yodrop.core.transport.LanTransport
import com.yodrop.core.transport.Transport

// TCP adapter only. WifiP2pManager group setup/teardown belongs to Step 16.
class WifiDirectTransport(@Suppress("UNUSED_PARAMETER") context: Context) : Transport by LanTransport()
