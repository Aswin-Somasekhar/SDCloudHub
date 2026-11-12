package com.sdcloud.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdcloud.hub.service.ServerStatus
import com.sdcloud.hub.storage.StorageManager
import com.sdcloud.hub.storage.StorageVolumeInfo
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: ServerStatus,
    storageManager: StorageManager,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val volumes = remember { storageManager.getAvailableStorageVolumes() }
    var selectedVolume by remember { mutableStateOf(volumes.firstOrNull { it.path == storageManager.getActiveRoot() } ?: volumes.firstOrNull()) }

    val qrBitmap: Bitmap? = remember(status.isRunning, status.localIp, status.httpPort) {
        if (status.isRunning) {
            QrCodeUtils.generateQrBitmap("http://${status.localIp}:${status.httpPort}/", 300)
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SD Cloud & Media Hub", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Status & Control Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.isRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (status.isRunning) Color(0xFF2E7D32) else Color(0xFFC62828))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (status.isRunning) "Server Active" else "Server Stopped",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = if (status.isRunning) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                            )
                            if (status.isRunning) {
                                Text(
                                    text = "IP: ${status.localIp}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { if (status.isRunning) onStopServer() else onStartServer() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (status.isRunning) Color(0xFFC62828) else Color(0xFF2E7D32)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (status.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (status.isRunning) "Stop" else "Start")
                    }
                }
            }

            // 2. Storage Selection & Capacity Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SdCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Active Storage Drive", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    volumes.forEach { volume ->
                        val isSelected = volume == selectedVolume
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(enabled = !status.isRunning) {
                                    selectedVolume = volume
                                    storageManager.setActiveRoot(volume.path)
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = volume.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = volume.path.absolutePath,
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                text = "${volume.usedPercent}% used",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    selectedVolume?.let { vol ->
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = vol.usedPercent / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val freeGb = String.format(Locale.US, "%.1f GB", vol.freeBytes / (1024.0 * 1024.0 * 1024.0))
                        val totalGb = String.format(Locale.US, "%.1f GB", vol.totalBytes / (1024.0 * 1024.0 * 1024.0))
                        Text(
                            text = "$freeGb free of $totalGb",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            if (status.isRunning) {
                // 3. Smart TV Streaming (DLNA / UPnP) Card
                ConnectionCard(
                    icon = Icons.Default.Tv,
                    title = "Smart TV (DLNA / UPnP)",
                    subtitle = "Automatically visible on Samsung, LG, Sony, Roku & Android TVs",
                    badge = "DLNA Media Server Active",
                    detail = "Look for 'SD Cloud Media Hub' in your TV's Media Player or Inputs menu."
                )

                // 4. Computer (WebDAV Cloud Drive) Card
                ConnectionCard(
                    icon = Icons.Default.Computer,
                    title = "PC & Mac (Network Drive)",
                    subtitle = "Mount as native Windows drive or Mac Finder share with Read & Write",
                    badge = "WebDAV Protocol",
                    url = "http://${status.localIp}:${status.httpPort}/webdav",
                    onCopy = { copyToClipboard(context, "http://${status.localIp}:${status.httpPort}/webdav") },
                    detail = "Windows: File Explorer -> Map Network Drive -> Enter URL\nMac: Finder -> Connect to Server (Cmd+K)"
                )

                // 5. FTP Server (Primitive FTPd compatibility)
                ConnectionCard(
                    icon = Icons.Default.Folder,
                    title = "FTP Server",
                    subtitle = "High-speed bulk transfers via FileZilla, WinSCP, or Android TV apps",
                    badge = "Port ${status.ftpPort}",
                    url = "ftp://${status.localIp}:${status.ftpPort}",
                    onCopy = { copyToClipboard(context, "ftp://${status.localIp}:${status.ftpPort}") }
                )

                // 6. Web Browser Dashboard & QR Code Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Web Browser Access", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Open this link in any browser to watch movies or upload files:",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        val webUrl = "http://${status.localIp}:${status.httpPort}/"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { copyToClipboard(context, webUrl) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(webUrl, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                        }

                        qrBitmap?.let { bitmap ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Scan QR code from phone or tablet:", fontSize = 12.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    url: String? = null,
    onCopy: (() -> Unit)? = null,
    detail: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)

            url?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onCopy?.invoke() }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                }
            }

            detail?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, fontSize = 11.sp, color = Color.Gray, lineHeight = 16.sp)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Copied URL", text))
    Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
}
