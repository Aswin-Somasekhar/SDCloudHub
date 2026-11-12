package com.sdcloud.hub.dlna

import com.sdcloud.hub.storage.MediaItem
import java.net.URLEncoder

object DidlLiteBuilder {

    fun buildDidlLite(
        items: List<MediaItem>,
        parentId: String,
        hostIp: String,
        httpPort: Int
    ): String {
        val sb = StringBuilder()
        sb.append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
        sb.append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        sb.append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
        sb.append("""xmlns:sec="http://www.sec.co.kr/">""")

        for (item in items) {
            val safeTitle = escapeXml(item.name)
            val itemId = escapeXml(item.id)
            val parent = escapeXml(parentId)

            if (item.isDirectory) {
                sb.append("""<container id="$itemId" parentID="$parent" restricted="1">""")
                sb.append("<dc:title>$safeTitle</dc:title>")
                sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
                sb.append("</container>")
            } else {
                val encodedPath = item.path.split("/").joinToString("/") { segment ->
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
                val streamUrl = "http://$hostIp:$httpPort/stream/$encodedPath"
                val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                val protocolInfo = "http-get:*:${item.mimeType}:$dlnaFlags"

                sb.append("""<item id="$itemId" parentID="$parent" restricted="1">""")
                sb.append("<dc:title>$safeTitle</dc:title>")
                sb.append("<upnp:class>${item.upnpClass}</upnp:class>")
                sb.append("""<res protocolInfo="$protocolInfo" size="${item.size}">$streamUrl</res>""")
                sb.append("</item>")
            }
        }

        sb.append("</DIDL-Lite>")
        return sb.toString()
    }

    fun buildSoapBrowseResponse(
        didlLiteXml: String,
        numberReturned: Int,
        totalMatches: Int
    ): String {
        val escapedDidl = escapeXml(didlLiteXml)
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>$escapedDidl</Result>
            <NumberReturned>$numberReturned</NumberReturned>
            <TotalMatches>$totalMatches</TotalMatches>
            <UpdateID>1</UpdateID>
        </u:BrowseResponse>
    </s:Body>
</s:Envelope>""".trimIndent()
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
