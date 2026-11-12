# SDCloudHub: Android SD Card Personal Cloud & DLNA Media Server

Combines the features of **Primitive ftpd** (network drive read/write via WebDAV & FTP) and **BubbleUPnP** (DLNA/UPnP media server with HTTP byte-range seeking) to turn any Android phone with an external MicroSD card into an always-on, high-performance home media server and cloud drive.

---

## Features

- **Smart TV Streaming (DLNA / UPnP)**:
  - Discovered automatically by Samsung Tizen, LG webOS, Sony Bravia, Android TV, and Roku.
  - No app required on the TV — shows up under **Inputs / Media Player**.
  - Supports instant seeking and resume using HTTP `206 Partial Content` Range requests.
- **PC & Mac Network Drive (WebDAV)**:
  - Mount directly in Windows Explorer as a drive letter (`Z:\`) or macOS Finder (`Cmd+K`).
  - Read and write files directly to the MicroSD card without root.
- **High-Speed FTP Server**:
  - Apache FtpServer on port `2121` for bulk transfers using FileZilla or WinSCP.
- **In-Browser Web Dashboard**:
  - Open `http://<phone-ip>:8080/` in any browser to watch videos and manage files.
  - QR Code generator built into the mobile app for instant connection.
- **Always-On Background Operation**:
  - Android Foreground Service with CPU WakeLock and High-Performance Wi-Fi Lock.
  - Automatically survives screen off and aggressive OEM battery killers.

---

## Project Structure

```
SDCloudHub/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/sdcloud/hub/
│           ├── MainActivity.kt               # Permission checks & Compose UI host
│           ├── storage/
│           │   ├── StorageManager.kt         # Discovers /storage/XXXX-XXXX (SD card)
│           │   └── MediaItem.kt              # Media data model & MIME mapping
│           ├── server/
│           │   ├── HttpServer.kt             # HTTP 206 Streaming + WebDAV + Web UI
│           │   └── FtpServerManager.kt       # Apache FTP Server (Primitive ftpd core)
│           ├── dlna/
│           │   ├── SsdpAdvertiser.kt         # UDP 1900 multicast announcer for TVs
│           │   ├── UpnpDeviceXml.kt          # UPnP AV Device description XML
│           │   └── DidlLiteBuilder.kt        # DIDL-Lite directory XML for TV browsing
│           ├── service/
│           │   ├── ServerForegroundService.kt# Foreground service with persistent notification
│           │   └── PowerAndWifiHelper.kt     # WakeLock + HighPerf WifiLock + IP discovery
│           └── ui/
│               ├── MainScreen.kt             # Jetpack Compose dashboard & storage picker
│               └── QrCodeUtils.kt            # QR code bitmap generator (ZXing)
```

---

## How to Build & Run

1. Open this directory in **Android Studio** (Hedgehog, Iguana, Jellyfish, or newer).
2. Allow Gradle sync to complete.
3. Connect an Android phone via USB with USB Debugging enabled.
4. Click **Run** (`Shift + F10`).
5. On the phone:
   - Grant **All Files Access** when prompted (required to read/write the MicroSD card).
   - Tap **Start** on the dashboard.
   - On your TV, open the **Media Player** to see your movies!
   - On your PC, map `http://<phone-ip>:8080/webdav` to read/write files!
