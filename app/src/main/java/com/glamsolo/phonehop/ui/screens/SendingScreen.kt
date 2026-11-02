package com.glamsolo.phonehop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.transfer.SendState
import com.glamsolo.phonehop.ui.Format
import com.glamsolo.phonehop.ui.components.Chip
import com.glamsolo.phonehop.ui.components.GhostButton
import com.glamsolo.phonehop.ui.components.GradientButton
import com.glamsolo.phonehop.ui.theme.Brand

@Composable
fun SendingScreen(
    state: SendState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (state.isActive) confirmCancel = true else onDone() }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(24.dp))
            when (state) {
                is SendState.Preparing -> WaitingView(
                    Icons.Rounded.Inventory2, Brand.Send, stringResource(R.string.preparing), null,
                )
                is SendState.Connecting -> WaitingView(
                    Icons.Rounded.WifiTethering, Brand.Send,
                    stringResource(R.string.connecting_to, state.target.name), null,
                )
                is SendState.WaitingForAnswer -> WaitingView(
                    Icons.Rounded.Smartphone, Brand.Send,
                    stringResource(R.string.waiting_answer, state.target.name),
                    stringResource(R.string.waiting_answer_hint),
                ) {
                    Spacer(Modifier.height(16.dp))
                    Chip(Format.manifestSummary(context, state.manifest), leading = Icons.Rounded.Inventory2)
                }
                is SendState.Transferring -> TransferProgressView(
                    stringResource(R.string.sending_to, state.receiverName),
                    state.progress,
                    Brand.Send,
                )
                is SendState.Finished -> ResultView(
                    Icons.Rounded.Check, Brand.Receive,
                    stringResource(R.string.sent_title),
                    stringResource(
                        R.string.done_summary,
                        Format.counts(context, state.manifest.appCount, state.manifest.fileCount),
                        Format.bytes(context, state.stats.bytes),
                        Format.duration(context, state.stats.durationMillis / 1000),
                    ) + "\n" + stringResource(R.string.average_speed, Format.speed(context, state.stats.averageBytesPerSecond)),
                )
                is SendState.Failed -> ResultView(
                    Icons.Rounded.ErrorOutline, listOf(Brand.Amber, Brand.Coral),
                    stringResource(R.string.failed_title),
                    Format.error(context, state.reason),
                )
                is SendState.Cancelled -> ResultView(
                    Icons.Rounded.Close, listOf(Brand.Amber, Brand.Coral),
                    stringResource(R.string.cancelled_title), null,
                )
                SendState.Idle -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        ) {
            when {
                state.isActive -> GhostButton(
                    text = stringResource(R.string.cancel_transfer),
                    onClick = { confirmCancel = true },
                    icon = Icons.Rounded.Close,
                    modifier = Modifier.fillMaxWidth(),
                )
                state is SendState.Failed || state is SendState.Cancelled -> {
                    GhostButton(stringResource(R.string.done), onDone, Modifier.weight(1f))
                    GradientButton(stringResource(R.string.retry), onRetry, Modifier.weight(1f), icon = Icons.Rounded.Refresh)
                }
                else -> GradientButton(
                    text = stringResource(R.string.done),
                    onClick = onDone,
                    gradient = Brand.Receive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmCancel) {
        CancelTransferDialog(
            onConfirm = {
                confirmCancel = false
                onCancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}
