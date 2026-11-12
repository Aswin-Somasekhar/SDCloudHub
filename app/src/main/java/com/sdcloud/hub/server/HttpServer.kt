package com.sdcloud.hub.server

import android.content.Context
import android.util.Log
import com.sdcloud.hub.dlna.DidlLiteBuilder
import com.sdcloud.hub.dlna.UpnpDeviceXml
import com.sdcloud.hub.storage.MediaItem
import com.sdcloud.hub.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HttpServer(
    private val context: Context,
    private val storageManager: StorageManager,
    private val port: Int = 8080,
    private val serverUuid: String,
    private val hostIp: String
) {
    companion object {
        private const val TAG = "HttpServer"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer for high-throughput video streaming
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "HTTP Server started on port $port")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase(Locale.ROOT)
                val fullUri = parts[1]
                val path = fullUri.substringBefore("?")

                // Read headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx != -1) {
                        val key = line.substring(0, idx).trim().lowercase(Locale.ROOT)
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                    }
                }

                // Route handling
                when {
                    // 1. UPnP Device & Service Description XML
                    method == "GET" && path == "/description.xml" -> {
                        val xml = UpnpDeviceXml.buildDeviceDescription("SD Cloud Media Hub", serverUuid, hostIp, port)
                        sendXmlResponse(output, 200, "OK", xml)
                    }
                    method == "GET" && path == "/upnp/scpd/content_directory.xml" -> {
                        sendXmlResponse(output, 200, "OK", UpnpDeviceXml.buildContentDirectoryScpd())
                    }
                    method == "GET" && path == "/upnp/scpd/connection_manager.xml" -> {
                        sendXmlResponse(output, 200, "OK", UpnpDeviceXml.buildConnectionManagerScpd())
                    }

                    // 2. UPnP SOAP Control Endpoints
                    method == "POST" && path == "/upnp/control/content_directory" -> {
                        handleContentDirectorySoap(input, output, headers)
                    }
                    method == "POST" && path == "/upnp/control/connection_manager" -> {
                        handleConnectionManagerSoap(output)
                    }

                    // 3. Media Streaming with HTTP Byte-Range support (for TV & Players)
                    (method == "GET" || method == "HEAD") && (path.startsWith("/stream/") || path.startsWith("/media/")) -> {
                        val relativePath = URLDecoder.decode(path.removePrefix("/stream/").removePrefix("/media/"), "UTF-8")
                        handleMediaStream(output, relativePath, headers, isHead = method == "HEAD")
                    }

                    // 4. WebDAV Methods (Windows Explorer / macOS Finder)
                    method == "OPTIONS" -> {
                        handleOptions(output)
                    }
                    method == "PROPFIND" -> {
                        handlePropfind(output, path, headers)
                    }
                    method == "MKCOL" -> {
                        handleMkcol(output, path)
                    }
                    method == "PUT" -> {
                        handlePut(input, output, path, headers)
                    }
                    method == "DELETE" -> {
                        handleDelete(output, path)
                    }
                    method == "MOVE" -> {
                        handleMove(output, path, headers)
                    }

                    // 5. Embedded HTML5 Web Browser Dashboard
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        sendHtmlResponse(output, 200, "OK", buildWebDashboardHtml())
                    }

                    // Default fallback
                    else -> {
                        sendResponse(output, 404, "Not Found", "text/plain", "Resource not found".toByteArray())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling client connection: ${e.message}")
            }
        }
    }

    private fun handleMediaStream(output: OutputStream, relativePath: String, headers: Map<String, String>, isHead: Boolean) {
        val file = storageManager.resolve(relativePath)
        if (file == null || !file.exists() || file.isDirectory) {
            sendResponse(output, 404, "Not Found", "text/plain", "File not found".toByteArray())
            return
        }

        val totalLength = file.length()
        val mimeType = storageManager.resolveMimeType(file)
        val rangeHeader = headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Parse Range: bytes=start-end
            val range = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = range.indexOf('-')
            var start = 0L
            var end = totalLength - 1L

            if (dashIdx != -1) {
                val startStr = range.substring(0, dashIdx).trim()
                val endStr = range.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) start = startStr.toLongOrNull() ?: 0L
                if (endStr.isNotEmpty()) end = endStr.toLongOrNull() ?: (totalLength - 1L)
            }

            if (start > end || start >= totalLength) {
                val header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\n\r\n"
                output.write(header.toByteArray())
                output.flush()
                return
            }

            end = end.coerceAtMost(totalLength - 1L)
            val chunkLength = end - start + 1L

            val responseHeaders = StringBuilder()
                .append("HTTP/1.1 206 Partial Content\r\n")
                .append("Content-Type: $mimeType\r\n")
                .append("Content-Length: $chunkLength\r\n")
                .append("Content-Range: bytes $start-$end/$totalLength\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Connection: keep-alive\r\n\r\n")

            output.write(responseHeaders.toString().toByteArray())
            output.flush()

            if (!isHead) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRemaining = chunkLength
                    while (bytesRemaining > 0 && isRunning) {
                        val toRead = bytesRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                        val bytesRead = raf.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        bytesRemaining -= bytesRead
                    }
                    output.flush()
                }
            }
        } else {
            // Full stream (200 OK)
            val responseHeaders = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: $mimeType\r\n")
                .append("Content-Length: $totalLength\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Connection: keep-alive\r\n\r\n")

            output.write(responseHeaders.toString().toByteArray())
            output.flush()

            if (!isHead) {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (isRunning) {
                        val bytesRead = fis.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun handleContentDirectorySoap(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readBody(input, contentLength)

        val objectId = if (body.contains("<ObjectID>")) {
            body.substringAfter("<ObjectID>").substringBefore("</ObjectID>").trim()
        } else "0"

        val browseFlag = if (body.contains("<BrowseFlag>")) {
            body.substringAfter("<BrowseFlag>").substringBefore("</BrowseFlag>").trim()
        } else "BrowseDirectChildren"

        val targetDir = if (objectId == "0" || objectId.isEmpty()) {
            storageManager.getActiveRoot()
        } else {
            storageManager.resolve(objectId) ?: storageManager.getActiveRoot()
        }

        val items = if (browseFlag == "BrowseMetadata") {
            val rootItem = MediaItem(
                id = objectId,
                name = targetDir.name,
                path = objectId,
                isDirectory = targetDir.isDirectory,
                size = targetDir.length(),
                lastModified = targetDir.lastModified(),
                mimeType = storageManager.resolveMimeType(targetDir)
            )
            listOf(rootItem)
        } else {
            storageManager.listItems(targetDir)
        }

        val didlLiteXml = DidlLiteBuilder.buildDidlLite(items, objectId, hostIp, port)
        val soapResponse = DidlLiteBuilder.buildSoapBrowseResponse(didlLiteXml, items.size, items.size)
        sendXmlResponse(output, 200, "OK", soapResponse)
    }

    private fun handleConnectionManagerSoap(output: OutputStream) {
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
            <Source>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:audio/mpeg:*</Source>
            <Sink></Sink>
        </u:GetProtocolInfoResponse>
    </s:Body>
</s:Envelope>""".trimIndent()
        sendXmlResponse(output, 200, "OK", soap)
    }

    // WebDAV Handlers
    private fun handleOptions(output: OutputStream) {
        val headers = "HTTP/1.1 200 OK\r\n" +
                "DAV: 1, 2\r\n" +
                "MS-Author-Via: DAV\r\n" +
                "Allow: OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY\r\n" +
                "Content-Length: 0\r\n\r\n"
        output.write(headers.toByteArray())
        output.flush()
    }

    private fun handlePropfind(output: OutputStream, rawPath: String, headers: Map<String, String>) {
        val relativePath = URLDecoder.decode(rawPath.removePrefix("/webdav/").removePrefix("/webdav"), "UTF-8")
        val target = storageManager.resolve(relativePath) ?: storageManager.getActiveRoot()
        val depth = headers["depth"] ?: "1"

        val itemsToReport = mutableListOf<File>()
        itemsToReport.add(target)
        if (target.isDirectory && depth != "0") {
            val children = target.listFiles() ?: emptyArray()
            itemsToReport.addAll(children)
        }

        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.append("""<D:multistatus xmlns:D="DAV:">""")

        for (file in itemsToReport) {
            val relPath = file.relativeTo(storageManager.getActiveRoot()).path.replace('\\', '/')
            val href = "/webdav/" + if (relPath.isEmpty()) "" else relPath
            val isDir = file.isDirectory
            val length = if (isDir) 0L else file.length()
            val lastMod = dateFormat.format(Date(file.lastModified()))

            sb.append("<D:response>")
            sb.append("<D:href>$href</D:href>")
            sb.append("<D:propstat>")
            sb.append("<D:prop>")
            sb.append("<D:displayname>${file.name}</D:displayname>")
            sb.append("<D:getlastmodified>$lastMod</D:getlastmodified>")
            if (isDir) {
                sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            } else {
                sb.append("<D:resourcetype/>")
                sb.append("<D:getcontentlength>$length</D:getcontentlength>")
                sb.append("<D:getcontenttype>${storageManager.resolveMimeType(file)}</D:getcontenttype>")
            }
            sb.append("</D:prop>")
            sb.append("<D:status>HTTP/1.1 200 OK</D:status>")
            sb.append("</D:propstat>")
            sb.append("</D:response>")
        }
        sb.append("</D:multistatus>")

        val responseBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val respHeader = "HTTP/1.1 207 Multi-Status\r\n" +
                "Content-Type: application/xml; charset=utf-8\r\n" +
                "Content-Length: ${responseBytes.size}\r\n\r\n"
        output.write(respHeader.toByteArray())
        output.write(responseBytes)
        output.flush()
    }

    private fun handleMkcol(output: OutputStream, rawPath: String) {
        val relPath = URLDecoder.decode(rawPath.removePrefix("/webdav/"), "UTF-8")
        val target = storageManager.resolve(relPath)
        if (target != null && target.mkdirs()) {
            output.write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray())
        } else {
            output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        output.flush()
    }

    private fun handlePut(input: InputStream, output: OutputStream, rawPath: String, headers: Map<String, String>) {
        val relPath = URLDecoder.decode(rawPath.removePrefix("/webdav/"), "UTF-8")
        val target = storageManager.resolve(relPath)
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        if (target != null) {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fos ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRemaining = contentLength
                if (bytesRemaining >= 0) {
                    while (bytesRemaining > 0) {
                        val toRead = bytesRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                } else {
                    // Chunked or unknown length
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                    }
                }
            }
            output.write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray())
        } else {
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        output.flush()
    }

    private fun handleDelete(output: OutputStream, rawPath: String) {
        val relPath = URLDecoder.decode(rawPath.removePrefix("/webdav/"), "UTF-8")
        val target = storageManager.resolve(relPath)
        if (target != null && target.exists()) {
            if (target.deleteRecursively()) {
                output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
            } else {
                output.write("HTTP/1.1 500 Internal Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        } else {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        output.flush()
    }

    private fun handleMove(output: OutputStream, rawPath: String, headers: Map<String, String>) {
        val relPath = URLDecoder.decode(rawPath.removePrefix("/webdav/"), "UTF-8")
        val destHeader = headers["destination"] ?: ""
        val destRel = URLDecoder.decode(destHeader.substringAfter("/webdav/"), "UTF-8")

        val srcFile = storageManager.resolve(relPath)
        val dstFile = storageManager.resolve(destRel)

        if (srcFile != null && dstFile != null && srcFile.renameTo(dstFile)) {
            output.write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray())
        } else {
            output.write("HTTP/1.1 409 Conflict\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        output.flush()
    }

    // Helper functions
    private fun sendXmlResponse(output: OutputStream, code: Int, msg: String, xml: String) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        sendResponse(output, code, msg, "text/xml; charset=utf-8", bytes)
    }

    private fun sendHtmlResponse(output: OutputStream, code: Int, msg: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        sendResponse(output, code, msg, "text/html; charset=utf-8", bytes)
    }

    private fun sendResponse(output: OutputStream, code: Int, msg: String, contentType: String, data: ByteArray) {
        val headers = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray())
        output.write(data)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val cur = input.read()
            if (cur == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (cur == '\n'.code && prev == '\r'.code) {
                return sb.substring(0, sb.length - 1)
            }
            sb.append(cur.toChar())
            prev = cur
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, totalRead, length - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return String(buffer, 0, totalRead, Charsets.UTF_8)
    }

    private fun buildWebDashboardHtml(): String {
        val root = storageManager.getActiveRoot()
        val items = storageManager.listItems(root)
        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SD Cloud Media Hub</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 24px; }
  h1 { color: #38bdf8; margin-bottom: 8px; }
  .card { background: #1e293b; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
  .item { background: #334155; padding: 14px; border-radius: 8px; text-decoration: none; color: #f8fafc; display: flex; flex-direction: column; }
  .item:hover { background: #475569; }
  .name { font-weight: bold; word-break: break-all; margin-bottom: 6px; }
  .meta { font-size: 12px; color: #94a3b8; }
  .btn { display: inline-block; background: #0284c7; color: white; padding: 8px 14px; border-radius: 6px; text-decoration: none; margin-top: 10px; text-align: center; font-weight: 500; }
  .btn:hover { background: #0369a1; }
</style>
</head>
<body>
  <div class="card">
    <h1>SD Cloud Media Hub</h1>
    <p>Storage Root: <code>${root.absolutePath}</code></p>
    <p>Connected via WebDAV at <code>http://$hostIp:$port/webdav</code> | DLNA Media Server active</p>
  </div>
  <div class="card">
    <h2>Files &amp; Movies</h2>
    <div class="grid">
""")

        for (item in items) {
            val isVideo = item.isVideo
            val sizeMb = String.format(Locale.US, "%.1f MB", item.size / (1024.0 * 1024.0))
            val url = if (item.isDirectory) "#" else "/stream/${item.path}"
            val actionText = if (isVideo) "Play Movie" else if (item.isDirectory) "Folder" else "Download"

            sb.append("""
      <div class="item">
        <div class="name">${item.name}</div>
        <div class="meta">${if (item.isDirectory) "Directory" else sizeMb}</div>
        <a class="btn" href="$url" ${if (isVideo) "target=\"_blank\"" else ""}>$actionText</a>
      </div>
""")
        }

        sb.append("""
    </div>
  </div>
</body>
</html>
""")
        return sb.toString()
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        Log.i(TAG, "HTTP Server stopped")
    }
}
