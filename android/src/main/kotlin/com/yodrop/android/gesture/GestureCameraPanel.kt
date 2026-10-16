package com.yodrop.android.gesture

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import com.yodrop.core.gesture.*

@Composable
fun GestureCameraPanel(owner: LifecycleOwner, context: GestureContext, onCommand: (GestureCommand) -> Unit) {
    val androidContext = LocalContext.current
    val commandCallback by rememberUpdatedState(onCommand)
    var status by remember { mutableStateOf("Starting camera...") }
    var pose by remember { mutableStateOf(HandPose.NONE) }
    var lastAction by remember { mutableStateOf<GestureAction?>(null) }
    var grabs by remember { mutableIntStateOf(0) }
    var releases by remember { mutableIntStateOf(0) }
    val view = remember { PreviewView(androidContext).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val camera = remember(owner, view) { AndroidGestureCamera(view, owner,
        onReady = { status = "Show one whole hand in good light" },
        onResult = { detected, command ->
            pose = detected
            if (command != null) {
                lastAction = command.action
                if (command.action == GestureAction.GRAB) grabs++ else releases++
                commandCallback(command)
            }
        }, onError = { status = it; pose = HandPose.NONE }) }
    SideEffect { camera.setContext(context) }
    DisposableEffect(camera, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) camera.start()
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) { camera.stop(); pose = HandPose.NONE }
        }
        owner.lifecycle.addObserver(observer)
        camera.start()
        onDispose { owner.lifecycle.removeObserver(observer); camera.stop() }
    }
    Column(Modifier.fillMaxWidth()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(200.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Hand: ${when (pose) { HandPose.PALM -> "Open palm"; HandPose.FIST -> "Fist"; HandPose.NONE -> "Not detected / more than one hand"; HandPose.UNKNOWN -> "Move hand fully into view" }}")
        Text(when (context.mode) {
            GestureMode.SEND -> "Hold an open palm, then close into a fist to send."
            GestureMode.CLAIM -> "Hold a fist, then open your palm to claim. Accept stays a separate tap."
            GestureMode.OBSERVE -> "Waiting for an offer. Then hold a fist and open your palm to claim."
            else -> "Hold each pose briefly: palm to fist = Grab; fist to palm = Release."
        }, style = MaterialTheme.typography.bodySmall)
        if (context.mode == GestureMode.PRACTICE) Text("Grab: $grabs  Release: $releases  Last: ${lastAction ?: "None"}")
        Text("Camera stays on this device. Frames are not saved.", style = MaterialTheme.typography.bodySmall)
    }
}
