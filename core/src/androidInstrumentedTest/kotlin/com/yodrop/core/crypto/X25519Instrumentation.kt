package com.yodrop.core.crypto

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle

class X25519Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            X25519Checks.runAll()
            result.putString(
                "stream",
                "\nX25519: 7 checks PASSED; API ${Build.VERSION.SDK_INT}\n"
            )
            result.putInt("apiLevel", Build.VERSION.SDK_INT)
            result.putInt("checksPassed", 7)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString(
                "stream",
                "\nX25519 FAILED; API ${Build.VERSION.SDK_INT}: $failure\n"
            )
            result.putString("shortMsg", failure.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}