package com.yodrop.android.gesture

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AndroidView(factory = { view }, modifier = Modifier.width(112.dp).height(144.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when (pose) { HandPose.PALM -> "Open palm"; HandPose.FIST -> "Fist"; HandPose.NONE -> "Show one hand"; HandPose.UNKNOWN -> "Keep your hand in view" },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(when (context.mode) {
                    GestureMode.SEND -> "Open palm → fist\nGrab your files"
                    GestureMode.CATCH -> "Fist → open palm\nCatch these files"
                    GestureMode.CLAIM -> "Fist → open palm\nReview this offer. Accept is a separate tap."
                    GestureMode.OBSERVE -> "Waiting for a verified offer. Gestures cannot accept anything yet."
                    else -> "Palm → fist: Grab\nFist → palm: Catch"
                }, style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.labelSmall)
                if (context.mode == GestureMode.PRACTICE) Text("Grab $grabs · Catch $releases\nLast: ${lastAction ?: "None"}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Camera stays on your phone. No frames saved.", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.labelSmall)
    }
}
