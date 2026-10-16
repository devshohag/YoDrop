package com.yodrop.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.SenderState

@Composable
fun MainScreen(
    senderState: SenderState,
    receiverState: ReceiverState,
    onSendClick: () -> Unit,
    onClaimClick: () -> Unit,
    peerName: String?,
    onReceiveClick: () -> Unit,
    onAcceptClick: () -> Unit,
    onDenyClick: () -> Unit,
    onCancelClick: () -> Unit,
    preparingFiles: Boolean = false,
    gestureSendingReady: Boolean = false,
    gestureFileSummary: String = "",
    practicingGestures: Boolean = false,
    onGestureSendClick: () -> Unit = {},
    onSendPreparedClick: () -> Unit = {},
    onPracticeClick: () -> Unit = {},
    onReceiverGestureClick: () -> Unit = {},
    gesturePanel: @Composable () -> Unit = {},
    trustPanel: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "YoDrop",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        gesturePanel()
        trustPanel()

        when {
            practicingGestures -> {
                Text("Practice only: no files are sent and no receiver is claimed.")
                OutlinedButton(onClick = onCancelClick) { Text("Close practice") }
            }
            preparingFiles -> {
                CircularProgressIndicator()
                Text("Preparing selected files...", modifier = Modifier.padding(top = 16.dp))
                OutlinedButton(onClick = onCancelClick) { Text("Cancel") }
            }
            gestureSendingReady -> {
                Text(gestureFileSummary)
                Text("Open palm, then fist to start sending. You can also tap Send now.")
                Button(onClick = onSendPreparedClick) { Text("Send now") }
                OutlinedButton(onClick = onCancelClick) { Text("Cancel selected files") }
            }
            senderState is SenderState.Idle && receiverState is ReceiverState.Idle -> {
                Button(
                    onClick = onSendClick,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text("Send Files")
                }
                OutlinedButton(onClick = onGestureSendClick, modifier = Modifier.fillMaxWidth()) { Text("Send with gesture") }
                OutlinedButton(
                    onClick = onReceiveClick,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text("Receive")
                }
                TextButton(onClick = onPracticeClick) { Text("Try gestures (one phone)") }
            }

            receiverState is ReceiverState.Scanning -> {
                Text("Waiting for an offer...")
                TextButton(onClick = onReceiverGestureClick) { Text("Use hand gesture") }
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }
            receiverState is ReceiverState.OfferSeen -> {
                Text("From: ${peerName ?: "Nearby device"}")
                Text("${receiverState.offer.fileCount} file(s), ${receiverState.offer.totalBytes} bytes")
                TextButton(onClick = onReceiverGestureClick) { Text("Use hand gesture") }
                Button(onClick = onClaimClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Receive this file")
                }
                OutlinedButton(onClick = onCancelClick) { Text("Cancel") }
            }

            receiverState is ReceiverState.Claimed -> {
                CircularProgressIndicator()
                Text("Reading encrypted file details...")
                Button(onClick = onCancelClick) { Text("Cancel") }
            }

            senderState is SenderState.Grabbed -> {
                CircularProgressIndicator()
                Text("Advertising... Waiting for receiver", modifier = Modifier.padding(top = 16.dp))
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }

            senderState is SenderState.AwaitAccept -> {
                Text("Verification Code:")
                Text(
                    text = senderState.verifyCode,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Text("Compare code with receiver and wait for acceptance.")
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }

            receiverState is ReceiverState.AcceptPrompt -> {
                Text("From: ${peerName ?: "Nearby device"}")
                Text("Incoming file: ${receiverState.previewName}")
                Text("${receiverState.offer.fileCount} file(s), ${receiverState.offer.totalBytes} bytes")
                Text("Save to Downloads/YoDrop")
                Text(
                    text = receiverState.verifyCode,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Text("Does this code match the sender?")
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onAcceptClick) {
                        Text("Accept")
                    }
                    OutlinedButton(onClick = onDenyClick) {
                        Text("Deny")
                    }
                }
            }

            senderState is SenderState.Linking || receiverState is ReceiverState.Linking -> {
                CircularProgressIndicator()
                Text("Accepted. Setting up connection...", modifier = Modifier.padding(top = 16.dp))
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) { Text("Cancel") }
            }

            senderState is SenderState.Cancelled || receiverState is ReceiverState.Cancelled -> {
                Text("Transfer cancelled")
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) { Text("Back to Home") }
            }

            senderState is SenderState.Transferring -> {
                Text("Sending: ${senderState.currentFileName}")
                LinearProgressIndicator(
                    progress = { senderState.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
                Text("${(senderState.progress * 100).toInt()}%")
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }

            receiverState is ReceiverState.Receiving -> {
                Text("Receiving: ${receiverState.currentFileName}")
                LinearProgressIndicator(
                    progress = { receiverState.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
                Text("${(receiverState.progress * 100).toInt()}%")
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }

            senderState is SenderState.Done || receiverState is ReceiverState.Done -> {
                Text("Transfer Complete!", style = MaterialTheme.typography.headlineMedium)
                if (receiverState is ReceiverState.Done) Text("Saved in Downloads/YoDrop")
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Back to Home")
                }
            }

            senderState is SenderState.Failed -> {
                Text(if (senderState.canResume) "Connection lost. Reconnecting..." else "Transfer Failed: ${senderState.error}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Reset")
                }
            }

            receiverState is ReceiverState.Failed -> {
                Text(if (receiverState.canResume) "Connection lost. Reconnecting..." else "Transfer Failed: ${receiverState.error}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onCancelClick, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Reset")
                }
            }
        }
    }
}
