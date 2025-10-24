# RTSP Recorder for Android

A robust Android application for recording RTSP (Real-Time Streaming Protocol) video streams to local storage with automatic segmentation, reconnection handling, and comprehensive logging.

## Features

### Core Functionality
- **RTSP Stream Recording**: Record video streams from IP cameras and RTSP sources
- **Automatic Segmentation**: Splits recordings into 3-minute segments for easier file management
- **Auto-Reconnection**: Automatically reconnects if the stream drops or network issues occur
- **Background Recording**: Continues recording even when the app is in the background
- **Persistent Storage**: Saves recordings to user-selected folders using Android's Storage Access Framework

### Advanced Features
- **Intelligent Retry Logic**: Adaptive reconnection delays based on failure count (3s → 10s)
- **Connection Watchdog**: Detects and handles connection timeouts (30-second limit)
- **Partial Segment Recovery**: Saves valid partial segments when connections drop
- **Wake Lock Management**: Keeps CPU active during recording to prevent interruptions
- **Real-time Logging**: Comprehensive logging system with timestamps
- **Log Export**: Save logs to text files for troubleshooting

## Technical Specifications

### Requirements
- **Minimum Android Version**: Android 5.0 (API 21)
- **Recommended**: Android 8.0+ (API 26+) for optimal notification support
- **Dependencies**:
  - LibVLC for Android (VLC media player library)
  - AndroidX libraries
  - DocumentFile provider

### Recording Parameters
- **Video Codec**: H.264
- **Video Bitrate**: 2000 kbps
- **Audio Codec**: MP4A (AAC)
- **Audio Bitrate**: 128 kbps
- **Container Format**: MP4
- **Segment Duration**: 3 minutes
- **Minimum Segment Size**: 100 KB

### Connection Settings
- **Network Caching**: 3000ms
- **File Caching**: 3000ms
- **RTSP Protocol**: TCP (forced)
- **Frame Buffer Size**: 500,000 bytes
- **Connection Timeout**: 30 seconds
- **Short Reconnect Delay**: 3 seconds
- **Long Reconnect Delay**: 10 seconds (after 5+ failures)

## Installation

### Prerequisites
1. Android Studio (latest stable version)
2. Android SDK with API 21+
3. LibVLC Android library

### Build Steps

1. **Clone or download the project**
   ```bash
   git clone <your-repo-url>
   cd rtsp-recorder
   ```

2. **Add LibVLC dependency** to `app/build.gradle`:
   ```gradle
   dependencies {
       implementation 'org.videolan.android:libvlc-all:3.5.0'
       // ... other dependencies
   }
   ```

3. **Configure AndroidManifest.xml** with required permissions:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.WAKE_LOCK" />
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
   ```

4. **Declare the service** in AndroidManifest.xml:
   ```xml
   <service
       android:name=".MainActivity$RecordingService"
       android:enabled="true"
       android:exported="false"
       android:foregroundServiceType="mediaProjection" />
   ```

5. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```

## Usage

### Basic Workflow

1. **Launch the app**
   - Grant necessary permissions when prompted

2. **Select output folder**
   - Tap "Select Output Folder"
   - Choose a directory where recordings will be saved
   - Grant persistent write permission

3. **Enter RTSP URL**
   - Format: `rtsp://username:password@ip:port/path`
   - Example: `rtsp://admin:password@192.168.1.100:554/stream1`

4. **Start recording**
   - Tap "Start Recording"
   - App will connect and begin recording segments
   - Status updates shown in notification and log

5. **Stop recording**
   - Tap "Stop Recording"
   - Final segment will be saved automatically

### RTSP URL Formats

Common RTSP URL patterns:
```
rtsp://192.168.1.100:554/stream1
rtsp://username:password@192.168.1.100:554/live.sdp
rtsp://camera.example.com:8554/h264
rtsps://secure-camera.example.com/stream  # Secure RTSP
```

### Log Management

- **View Logs**: Real-time logs displayed in the scrollable log area
- **Toggle Logging**: Tap "Log: ON/OFF" to enable/disable UI logging
- **Clear Logs**: Tap "Clear Log" to clear the log display
- **Save Logs**: Tap "Save Log" to export logs to a timestamped text file

## File Output

### Segment Naming Convention
```
recording_segment_0_1234567890.mp4
recording_segment_1_1234567891.mp4
recording_segment_2_1234567892.mp4
...
```

### Log File Naming
```
recording_log_20250124_143025.txt
```

## Troubleshooting

### Connection Issues

**Problem**: "Connection timeout" after 30 seconds
- **Solution**: Check camera credentials and network connectivity
- Verify RTSP URL format
- Ensure camera supports RTSP over TCP

**Problem**: Frequent reconnections
- **Solution**: Check network stability
- Consider increasing network caching values
- Verify camera stream is stable

### Storage Issues

**Problem**: "Output folder not accessible"
- **Solution**: Re-select the output folder
- Check available storage space
- Ensure app has persistent URI permissions

**Problem**: Segments not saving
- **Solution**: Check minimum segment size requirements (100 KB)
- Verify write permissions
- Check available disk space (app cache + output folder)

### Performance Issues

**Problem**: App stops recording in background
- **Solution**: Disable battery optimization for the app
- Settings → Apps → RTSP Recorder → Battery → Unrestricted

**Problem**: High battery usage
- **Solution**: This is expected due to continuous recording
- Wake lock keeps CPU active
- Consider reducing video bitrate if needed

## Advanced Configuration

### Modifying Recording Parameters

Edit constants in `RecordingService` class:

```java
// Segment duration (default: 3 minutes)
private static final long SEGMENT_DURATION_MS = 3 * 60 * 1000;

// Reconnection delays
private static final long RECONNECT_DELAY_SHORT_MS = 3000;
private static final long RECONNECT_DELAY_LONG_MS = 10000;

// Minimum valid segment size
private static final long MIN_SEGMENT_SIZE_BYTES = 1024 * 100;

// Connection timeout
private static final long CONNECTION_TIMEOUT_MS = 30000;
```

### Modifying Video Quality

In `startNewSegment()` method, adjust the sout chain:

```java
// Current: H.264 @ 2000kbps, AAC @ 128kbps
String soutChain = ":sout=#transcode{vcodec=h264,vb=2000,acodec=mp4a,ab=128}:...";

// Higher quality example:
String soutChain = ":sout=#transcode{vcodec=h264,vb=4000,acodec=mp4a,ab=192}:...";

// Lower quality example:
String soutChain = ":sout=#transcode{vcodec=h264,vb=1000,acodec=mp4a,ab=96}:...";
```

## Architecture

### Component Overview

```
MainActivity
├── UI Management
├── Preference Storage
├── Service Binding
└── Log Display

RecordingService (Foreground Service)
├── LibVLC Integration
├── Stream Connection Management
├── Segment Recording
├── File I/O Operations
├── Reconnection Logic
└── Wake Lock Management
```

### State Management

The service maintains several states:
- **DISCONNECTED**: No active connection
- **CONNECTING**: Attempting to establish connection
- **CONNECTED**: Successfully streaming and recording
- **RECONNECTING**: Connection lost, attempting to reconnect
- **ERROR**: Encountered an error

## Known Limitations

1. **No Live Preview**: App records without displaying video preview
2. **Fixed Segment Duration**: 3-minute segments (hard-coded)
3. **No Concurrent Streams**: Records one stream at a time
4. **No Streaming Formats**: Only saves to local MP4 files
5. **Limited Error Details**: LibVLC errors may lack specific details

## Future Enhancements

- [ ] Live video preview
- [ ] Configurable segment duration
- [ ] Multiple concurrent stream recording
- [ ] Cloud storage upload support
- [ ] Motion detection recording
- [ ] Scheduled recording
- [ ] Network bandwidth monitoring
- [ ] Custom transcoding profiles

## Security Considerations

- **Credentials in URL**: RTSP credentials are stored in SharedPreferences (unencrypted)
- **Network Security**: Use RTSPS for encrypted streams when possible
- **Storage Security**: Recordings saved to user-selected folders without encryption
- **Wake Lock**: Held continuously during recording (impacts battery)

## License

[Specify your license here]

## Support

For issues, questions, or contributions:
- Create an issue in the repository
- Check existing issues for solutions
- Review logs for detailed error information

## Credits

Built with:
- [LibVLC for Android](https://www.videolan.org/) - Video streaming and transcoding
- [AndroidX Libraries](https://developer.android.com/jetpack/androidx) - Modern Android development
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) - File system access

## Version History

### v1.0.0 (Current)
- Initial release
- RTSP stream recording with segmentation
- Automatic reconnection
- Comprehensive logging
- Background recording support
- Wake lock management
