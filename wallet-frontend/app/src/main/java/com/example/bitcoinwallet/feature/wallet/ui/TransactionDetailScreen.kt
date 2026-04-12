package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.bitcoinwallet.core.api.TxInputDto
import com.example.bitcoinwallet.core.api.TxOutputDto
import com.example.bitcoinwallet.feature.wallet.viewmodel.TransactionDetailUiState
import com.example.bitcoinwallet.ui.theme.*

@Composable
fun TransactionDetailScreen(
    state: TransactionDetailUiState,
    onClose: () -> Unit,
    onBack: () -> Unit = onClose,
    depth: Int = 1,
    maxDepth: Int = 5,
    highlightOutputIndex: Int? = null,
    onOpenPrevTx: (String, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var selectedInput by remember { mutableStateOf<TxInputDto?>(null) }
    var selectedOutput by remember { mutableStateOf<TxOutputDto?>(null) }
    val canDrillDown = depth < maxDepth
    val canGoBack = depth > 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Transaction Detail",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
                Text(
                    text = "Depth $depth / $maxDepth",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            if (canGoBack) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = AccentTeal) }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error,
                        color = ErrorRed,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    SectionLabel("Transaction ID")
                    Text(
                        text = state.txid,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    InfoCard {
                        InfoRow(
                            "Status",
                            state.confirmations,
                            valueColor = if (state.confirmed) ReceiveGreen else AccentTeal
                        )
                        if (state.blockHeight != null) InfoRow("Block", "#${state.blockHeight}")
                        if (state.timestamp.isNotBlank()) InfoRow("Date", state.timestamp)
                    }

                    Spacer(Modifier.height(12.dp))

                    InfoCard {
                        InfoRow("Fee", state.feeBtc)
                        if (state.feeRate.isNotBlank()) InfoRow("Fee rate", state.feeRate)
                        if (state.size > 0) InfoRow("Size", "${state.size} bytes")
                    }

                    Spacer(Modifier.height(20.dp))

                    IoSectionHeader(
                        title = "Inputs",
                        count = state.inputs.size,
                        icon = Icons.Filled.ArrowUpward,
                        iconTint = BitcoinOrange
                    )
                    state.inputs.forEachIndexed { idx, input ->
                        TxIoRow(
                            address = input.address,
                            valueSats = input.valueSats,
                            isMine = input.isMine,
                            onClick = { selectedInput = input }
                        )
                        if (idx < state.inputs.lastIndex) Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(20.dp))

                    IoSectionHeader(
                        title = "Outputs",
                        count = state.outputs.size,
                        icon = Icons.Filled.ArrowDownward,
                        iconTint = ReceiveGreen
                    )
                    state.outputs.forEachIndexed { idx, output ->
                        TxIoRow(
                            address = output.address,
                            valueSats = output.valueSats,
                            isMine = output.isMine,
                            highlighted = highlightOutputIndex == output.index,
                            onClick = { selectedOutput = output }
                        )
                        if (idx < state.outputs.lastIndex) Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    selectedInput?.let { input ->
        IoDetailSheet(
            title = "Input detail",
            address = input.address,
            valueSats = input.valueSats,
            prevTxid = input.txid,
            prevVout = input.vout,
            canDrillDown = canDrillDown,
            onDismiss = { selectedInput = null },
            onOpenPrevTx = {
                selectedInput = null
                onOpenPrevTx(input.txid, input.vout)
            }
        )
    }

    selectedOutput?.let { output ->
        IoDetailSheet(
            title = "Output detail",
            address = output.address,
            valueSats = output.valueSats,
            outputIndex = output.index,
            onDismiss = { selectedOutput = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun TagChip(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun IoSectionHeader(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "($count)",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
    }
}

@Composable
private fun TxIoRow(
    address: String,
    valueSats: Long,
    isMine: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val btcAmount = valueSats / 100_000_000.0
    val shortAddr = if (address.length > 20)
        "${address.take(10)}…${address.takeLast(8)}" else address

    val accent = when {
        highlighted -> BitcoinOrange
        isMine -> AccentTeal
        else -> DividerColor
    }
    val rowShape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(DarkSurface)
            .border(width = 1.dp, color = accent.copy(alpha = if (isMine) 0.6f else 0.25f), shape = rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shortAddr,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMine) {
                    Spacer(Modifier.width(8.dp))
                    TagChip(text = "YOURS", color = AccentTeal)
                }
                if (highlighted) {
                    Spacer(Modifier.width(6.dp))
                    TagChip(text = "FROM HERE", color = BitcoinOrange)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "%.8f BTC".format(btcAmount),
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IoDetailSheet(
    title: String,
    address: String,
    valueSats: Long,
    prevTxid: String? = null,
    prevVout: Int? = null,
    outputIndex: Int? = null,
    canDrillDown: Boolean = false,
    onDismiss: () -> Unit,
    onOpenPrevTx: (() -> Unit)? = null
) {
    val btcAmount = valueSats / 100_000_000.0
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            val indexLabel = outputIndex?.let { "#$it" } ?: prevVout?.let { "#$it" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (indexLabel != null) {
                    Text(
                        text = indexLabel,
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkBackground)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "%.8f BTC".format(btcAmount),
                color = AccentTeal,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$valueSats sats",
                color = TextMuted,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(20.dp))

            SectionLabel("Address")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = address,
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }

            if (prevTxid != null) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Previous transaction")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = prevTxid,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (onOpenPrevTx != null && canDrillDown) {
                    Button(
                        onClick = onOpenPrevTx,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                    ) {
                        Text(
                            "Open previous transaction",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else if (!canDrillDown) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkBackground)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Max depth reached",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TransactionDetailPreview() {
    TransactionDetailScreen(
        state = TransactionDetailUiState(
            txid = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            confirmed = true,
            confirmations = "Confirmed",
            blockHeight = 840123,
            timestamp = "2025-12-15 14:32",
            feeSats = 3420,
            size = 225,
            weight = 573,
            inputs = listOf(
                TxInputDto(
                    txid = "prev_tx_001",
                    vout = 0,
                    address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                    valueSats = 150000,
                    isMine = true
                )
            ),
            outputs = listOf(
                TxOutputDto(
                    index = 0,
                    address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                    valueSats = 120000,
                    isMine = false
                )
            ),
            isLoading = false
        ),
        onClose = {}
    )
}
