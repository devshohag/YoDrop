package com.yodrop.android.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.session.*
import com.yodrop.core.trust.*

private val AirColors = darkColorScheme(
    primary = Color(0xFF8AF0C7), onPrimary = Color(0xFF07382A),
    secondary = Color(0xFFAFC6FF), background = Color(0xFF10151F),
    surface = Color(0xFF19222F), surfaceVariant = Color(0xFF253143),
    onBackground = Color(0xFFF3F6FC), onSurface = Color(0xFFF3F6FC),
    onSurfaceVariant = Color(0xFFB7C4D6)
)

@Composable
fun MainScreen(
    senderState: SenderState, receiverState: ReceiverState,
    onSendClick: () -> Unit, onClaimClick: () -> Unit, peerName: String?,
    onReceiveClick: () -> Unit, onAcceptClick: () -> Unit, onDenyClick: () -> Unit,
    onCancelClick: () -> Unit, preparingFiles: Boolean = false,
    preparedFiles: List<FileMeta> = emptyList(), previewUri: Uri? = null,
    catchTicket: TrustedCatchTicket? = null, trustedWindow: ReceiveWindow? = null,
    trustedSession: Boolean = false,
    peerIdentity: PeerIdentityStatus? = null, cameraEnabled: Boolean = false,
    practicingGestures: Boolean = false, onSendPreparedClick: () -> Unit = {},
    onPracticeClick: () -> Unit = {}, onCameraToggle: () -> Unit = {}, onCatchClick: () -> Unit = {},
    gesturePanel: @Composable () -> Unit = {}, trustPanel: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun summary(count: Int, bytes: Long) = "$count file${if (count == 1) "" else "s"} · ${Formatter.formatShortFileSize(context, bytes)}"
    val transfer = senderState as? SenderState.Transferring
    val receiving = receiverState as? ReceiverState.Receiving
    val progress = transfer?.progress ?: receiving?.progress
    val done = senderState is SenderState.Done || receiverState is ReceiverState.Done
    val waiting = senderState is SenderState.Grabbed || senderState is SenderState.AwaitAccept ||
        receiverState is ReceiverState.Scanning || receiverState is ReceiverState.Claimed ||
        senderState is SenderState.Linking || receiverState is ReceiverState.Linking
    MaterialTheme(colorScheme = AirColors) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("YoDrop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("NEARBY · OFFLINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                AirTransferVisual(waiting, progress, done, catchTicket != null)
                when {
                    practicingGestures -> {
                        Heading("Try a Grab. Try a Catch.", "Practice only. No files are sent.")
                        gesturePanel()
                        SecondaryAction("Close practice", onCancelClick)
                    }
                    preparingFiles -> {
                        Heading("Preparing your preview", "Checking selected files. Large files can take a moment.")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        SecondaryAction("Cancel", onCancelClick)
                    }
                    preparedFiles.isNotEmpty() -> {
                        Heading("Ready to Grab", "Open your palm, then close it to hold these files for a nearby receiver.")
                        FilePreviewCard(preparedFiles, previewUri)
                        gesturePanel()
                        PrimaryAction("Grab files", onSendPreparedClick)
                        CameraChoice(cameraEnabled, onCameraToggle)
                        SecondaryAction("Discard selection", onCancelClick)
                    }
                    senderState is SenderState.Idle && receiverState is ReceiverState.Idle -> {
                        Heading("A little closer.\nA lot simpler.", "Preview your files, Grab to share, and Catch on the other phone.")
                        PrimaryAction("Select files", onSendClick)
                        SecondaryAction("Receive from a new device", onReceiveClick)
                        Text("No internet needed. Keep Bluetooth and Wi-Fi on.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        trustPanel()
                        TextButton(onClick = onPracticeClick) { Text("Try gestures with one phone") }
                    }
                    receiverState is ReceiverState.Scanning -> {
                        Heading(if (trustedWindow != null) "Waiting for your sender" else "Looking nearby",
                            if (trustedWindow != null) "Ask the sender to Grab their selected files. Their identity is checked before Catch appears."
                            else "The other phone should select files and tap or gesture Grab.")
                        gesturePanel()
                        CameraChoice(cameraEnabled, onCameraToggle)
                        trustPanel()
                        SecondaryAction("Cancel", onCancelClick)
                    }
                    receiverState is ReceiverState.OfferSeen -> {
                        Heading(if (trustedWindow != null) "Checking the sender" else "An offer is nearby", "${peerName ?: "Nearby device"} · ${summary(receiverState.offer.fileCount, receiverState.offer.totalBytes)}")
                        if (trustedWindow == null) {
                            Text("File details stay private until you connect.", style = MaterialTheme.typography.bodySmall)
                            gesturePanel()
                            PrimaryAction("Review this offer", onClaimClick)
                            CameraChoice(cameraEnabled, onCameraToggle)
                        }
                        trustPanel()
                        SecondaryAction("Cancel", onCancelClick)
                    }
                    receiverState is ReceiverState.Claimed -> {
                        Heading("Checking the sender", "Reading their signed identity and encrypted file preview. Nothing is accepted yet.")
                        gesturePanel()
                        trustPanel()
                        SecondaryAction("Cancel", onCancelClick)
                    }
                    senderState is SenderState.Grabbed -> {
                        Heading("You've got it", "Your files are ready in the air. On the other phone, choose you under trusted devices and Catch.")
                        Text("First time? Use Receive from a new device on the other phone.", style = MaterialTheme.typography.bodySmall)
                        SecondaryAction("Cancel sending", onCancelClick)
                    }
                    senderState is SenderState.AwaitAccept -> {
                        Heading("Waiting for their Catch", if (peerIdentity?.trusted == true) "${peerIdentity.label} · identity verified"
                            else "${peerName ?: "Nearby device"} is reviewing your files. Compare codes for a first connection.")
                        TransferCode(senderState.verifyCode)
                        trustPanel()
                        SecondaryAction("Cancel sending", onCancelClick)
                    }
                    receiverState is ReceiverState.AcceptPrompt -> {
                        if (catchTicket != null) {
                            Heading("Ready to Catch", "${peerIdentity?.label ?: "Trusted sender"} · identity verified")
                            IncomingPreview(receiverState.previewName, summary(receiverState.offer.fileCount, receiverState.offer.totalBytes))
                            Text("Hold a fist, then open your palm to receive. Or tap Catch files.")
                            gesturePanel()
                            PrimaryAction("Catch files", onCatchClick)
                            CameraChoice(cameraEnabled, onCameraToggle)
                            trustPanel()
                            SecondaryAction("Decline", onDenyClick)
                        } else if (trustedSession) {
                            Heading("Catch window closed", "Open a new receive window for your trusted sender.")
                            SecondaryAction("Back to home", onCancelClick)
                        } else {
                            Heading("Check before receiving", "From ${peerName ?: "Nearby device"}")
                            IncomingPreview(receiverState.previewName, summary(receiverState.offer.fileCount, receiverState.offer.totalBytes))
                            TransferCode(receiverState.verifyCode)
                            Text("Compare this code on both phones. Accept only if it matches.")
                            trustPanel()
                            PrimaryAction("Accept files", onAcceptClick)
                            SecondaryAction("Decline", onDenyClick)
                        }
                    }
                    senderState is SenderState.Linking || receiverState is ReceiverState.Linking ||
                        senderState is SenderState.Auth || receiverState is ReceiverState.Auth -> {
                        Heading("Connecting your phones", "Setting up the encrypted transfer.")
                        SecondaryAction("Cancel", onCancelClick)
                    }
                    transfer != null || receiving != null -> {
                        Heading(if (transfer != null) "On its way" else "Catching your files", transfer?.currentFileName ?: receiving!!.currentFileName)
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${((progress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
                        SecondaryAction("Cancel transfer", onCancelClick)
                    }
                    receiverState is ReceiverState.Verifying -> Heading("Almost there", "Checking your files before saving them.")
                    done -> {
                        Heading(if (receiverState is ReceiverState.Done) "Caught and saved" else "Delivered",
                            if (receiverState is ReceiverState.Done) "Find your files in Downloads/YoDrop." else "Your files arrived on the other phone.")
                        PrimaryAction("Done", onCancelClick)
                    }
                    senderState is SenderState.Failed || receiverState is ReceiverState.Failed -> {
                        val failure = senderState as? SenderState.Failed
                        val other = receiverState as? ReceiverState.Failed
                        val resume = failure?.canResume == true || other?.canResume == true
                        Heading(if (resume) "Reconnecting" else "Couldn't finish", if (resume) "Keep both phones nearby with Wi-Fi on. Your transfer can resume."
                            else failure?.error ?: other!!.error)
                        SecondaryAction(if (resume) "Cancel" else "Back to home", onCancelClick)
                    }
                    else -> {
                        Heading("Transfer closed", "Your phones are ready for a fresh start.")
                        PrimaryAction("Back to home", onCancelClick)
                    }
                }
            }
        }
    }
}

@Composable private fun Heading(title: String, detail: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PrimaryAction(label: String, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(label, style = MaterialTheme.typography.titleMedium) }
}
@Composable private fun SecondaryAction(label: String, action: () -> Unit) {
    OutlinedButton(action, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
@Composable private fun CameraChoice(enabled: Boolean, action: () -> Unit) {
    TextButton(action) { Text(if (enabled) "Turn camera off · use buttons" else "Use hand gestures") }
}
@Composable private fun TransferCode(code: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("TRANSFER CODE", style = MaterialTheme.typography.labelSmall)
            Text(code, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable private fun IncomingPreview(name: String, summary: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Saves to Downloads/YoDrop", style = MaterialTheme.typography.bodySmall)
        }
    }
}
