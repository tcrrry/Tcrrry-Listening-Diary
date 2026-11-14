package com.tcrrry.diary

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope

internal fun boostViewFrameRate(view: View) {
    if (Build.VERSION.SDK_INT >= 35) {
        runCatching { view.setRequestedFrameRate(120f) }
    }
}

class MainActivity : ComponentActivity() {
    lateinit var controller: DiaryController; private set
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) controller.importJson(uri) }
    private val appleImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) controller.stageApple(uri) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) controller.exportJson(uri) }
    private var pendingLanAction:(()->Unit)?=null
    private val lanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        val action=pendingLanAction;pendingLanAction=null
        if(granted)action?.invoke() else if(::controller.isInitialized)controller.lanPermissionDenied()
    }
    internal fun withLanPermission(action:()->Unit){
        val permission="android.permission.ACCESS_LOCAL_NETWORK"
        if(Build.VERSION.SDK_INT<37 || checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED)action()
        else{pendingLanAction=action;lanPermission.launch(permission)}
    }

    private fun applyHighRefreshRate() {
        runCatching {
            val currentDisplay = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val active = currentDisplay?.mode
            val best = currentDisplay?.supportedModes
                ?.filter { active == null || (it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight) }
                ?.maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                if (lp.preferredDisplayModeId != best.modeId || lp.preferredRefreshRate != best.refreshRate) {
                    lp.preferredDisplayModeId = best.modeId
                    lp.preferredRefreshRate = best.refreshRate
                    window.attributes = lp
                }
            }
            boostViewFrameRate(window.decorView)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
        window.statusBarColor = android.graphics.Color.rgb(11, 13, 19)
        window.navigationBarColor = android.graphics.Color.rgb(11, 13, 19)
        controller = DiaryController(this, lifecycleScope, savedInstanceState)
        setContent {
            DiaryApp(
                controller,
                { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                { export.launch("Listening-Diary-${java.time.LocalDate.now()}.json") },
                { appleImport.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/csv", "text/comma-separated-values", "application/octet-stream")) }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyHighRefreshRate()
    }

    override fun onStart() {
        super.onStart()
        applyHighRefreshRate()
        if (::controller.isInitialized) controller.start()
    }

    override fun onStop() {
        if (::controller.isInitialized) controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        controller.save(outState)
        super.onSaveInstanceState(outState)
    }
}
