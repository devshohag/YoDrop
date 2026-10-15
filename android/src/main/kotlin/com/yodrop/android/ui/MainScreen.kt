package com.yodrop.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.SenderState

@Composable
fun MainScreen(
    senderState: SenderState,
    receiverState: ReceiverState,
    onSendTapped: () -> Unit,
    onReceiveTapped: () -> Unit,
    onAcceptTapped: () -> Unit,
    onDenyTapped: () -> Unit,
    onCancelTapped: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "YoDrop",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "High-Speed Offline P2P Transfer",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Central Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        senderState is SenderState.Advertising -> {
                            Text("Broadcasting Transfer...", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("File: ${senderState.offer.previewName}")
                            Text("Size: ${senderState.offer.totalBytes / (1024 * 1024)} MB")
                            CircularProgressIndicator(Modifier.padding(top = 16.dp))
                        }
                        senderState is SenderState.AwaitAccept -> {
                            Text("Compare 4-Digit Verification Code:", fontWeight = FontWeight.Medium)
                            Text(
                                text = senderState.verifyCode,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                            Text("Confirm this matches the receiver's phone.")
                        }
                        receiverState is ReceiverState.AcceptPrompt -> {
                            Text("Incoming Transfer Request!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("File: ${receiverState.offer.previewName}")
                            Text(
                                text = receiverState.verifyCode,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Button(
                                    onClick = onAcceptTapped,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    Text("Accept")
                                }
                                OutlinedButton(onClick = onDenyTapped) {
                                    Text("Decline")
                                }
                            }
                        }
                        senderState is SenderState.Transferring -> {
                            Text("Sending: ${senderState.currentFile}")
                            LinearProgressIndicator(
                                progress = { senderState.progressPercent },
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                        receiverState is ReceiverState.Receiving -> {
                            Text("Receiving: ${receiverState.currentFile}")
                            LinearProgressIndicator(
                                progress = { receiverState.progressPercent },
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                        senderState is SenderState.Done || receiverState is ReceiverState.Done -> {
                            Text("✅ Transfer Finished!", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        else -> {
                            Text("Ready to share files.")
                            Text("Open camera for Gesture or tap buttons below.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            // Bottom Action Controls (Tap Fallback)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onSendTapped,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Send File (Tap)")
                }
                OutlinedButton(
                    onClick = onReceiveTapped,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Receive (Tap)")
                }
            }
        }
    }
}
