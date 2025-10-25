package com.example.rtsprecorder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager; // <-- WAKELOCK IMPORT
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException; // Added for IOException
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // Keys for storing settings
    public static final String PREFS_NAME = "RTSPRecorderPrefs";
    public static final String KEY_RTSP_URL = "lastRtspUrl";
    public static final String KEY_FOLDER_URI = "lastFolderUri";
    public static final String KEY_LOGGING_ENABLED = "loggingEnabled";

    // UI Elements
    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private TextView outputFilePathTextView;
    private TextView logTextView;
    private Button clearLogButton;
    private Button saveLogButton;
    private Button toggleLogButton;

    // State Variables
    private Uri outputFolderUri;
    private final StringBuilder logBuilder = new StringBuilder();
    private RecordingService recordingService;
    private boolean isBound = false;
    private boolean isRecording = false;
    private boolean isLoggingEnabled = true;

    // Handles result from folder selection
    private final ActivityResultLauncher<Intent> selectOutputFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                            outputFolderUri = result.getData().getData();
                            if (outputFolderUri != null) {
                                // Persist permission
                                getContentResolver().takePersistableUriPermission(
                                        outputFolderUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                );
                                outputFilePathTextView.setText("Folder: " + outputFolderUri.getLastPathSegment());
                                addLog("Output folder selected: " + outputFolderUri.getLastPathSegment());
                                // Save URI
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        .edit()
                                        .putString(KEY_FOLDER_URI, outputFolderUri.toString())
                                        .apply();
                            }
                        }
                    });

    // Manages connection to the RecordingService
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            RecordingService.RecordingBinder binder = (RecordingService.RecordingBinder) service;
            recordingService = binder.getService();
            recordingService.setLogCallback(MainActivity.this::addLog);
            isBound = true;
            if (recordingService.isRecording()) {
                isRecording = true;
                startRecordingButton.setText("Stop Recording");
                addLog("Connected to recording service (already recording)");
            } else {
                addLog("Connected to recording service");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            recordingService = null;
            addLog("Disconnected from recording service");
        }
    };

    // Activity initialization
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get UI element references
        rtspUrlEditText = findViewById(R.id.rtspUrl);
        startRecordingButton = findViewById(R.id.startRecording);
        Button selectOutputFolderButton = findViewById(R.id.selectOutputFile);
        outputFilePathTextView = findViewById(R.id.outputFilePath);
        logTextView = findViewById(R.id.logTextView);
        clearLogButton = findViewById(R.id.clearLogButton);
        saveLogButton = findViewById(R.id.saveLogButton);
        toggleLogButton = findViewById(R.id.toggleLogButton);

        // Load saved preferences
        loadPreferences();

        selectOutputFolderButton.setText("Select Output Folder");
        addLog("Application started");

        // Set button actions
        setupButtonClickListeners();
    }

    // Loads settings from SharedPreferences
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        rtspUrlEditText.setText(prefs.getString(KEY_RTSP_URL, "")); // Load URL

        // Load Folder URI and check permission
        String savedUriString = prefs.getString(KEY_FOLDER_URI, null);
        if (savedUriString != null) {
            Uri tempUri = Uri.parse(savedUriString);
            if (checkFolderPermission(tempUri)) {
                outputFolderUri = tempUri;
                outputFilePathTextView.setText("Folder: " + outputFolderUri.getLastPathSegment());
                addLog("Loaded saved output folder: " + outputFolderUri.getLastPathSegment());
            } else {
                addLog("Saved folder permission lost, please re-select.");
                prefs.edit().remove(KEY_FOLDER_URI).apply();
            }
        }

        // Load logging state
        isLoggingEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, true);
        updateToggleLogButtonText();
    }

    // Checks persisted permissions for a folder URI
    private boolean checkFolderPermission(Uri uri) {
        for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.isWritePermission() && perm.getUri().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    // Sets onClickListeners for all buttons
    private void setupButtonClickListeners() {
        Button selectOutputFolderButton = findViewById(R.id.selectOutputFile);

        selectOutputFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            selectOutputFolderLauncher.launch(intent);
            addLog("Opening folder picker...");
        });

        startRecordingButton.setOnClickListener(v -> {
            if (isRecording) { // Stop recording
                addLog("Stopping recording...");
                stopRecordingService();
                isRecording = false;
                startRecordingButton.setText("Start Recording");
            } else { // Start recording
                String rtspUrl = rtspUrlEditText.getText().toString().trim();
                if (rtspUrl.isEmpty() || outputFolderUri == null) {
                    Toast.makeText(this, rtspUrl.isEmpty() ? "Please enter URL" : "Please select folder", Toast.LENGTH_SHORT).show();
                    addLog(rtspUrl.isEmpty() ? "ERROR: RTSP URL empty" : "ERROR: Output folder not selected");
                    return;
                }
                addLog("Starting recording: " + rtspUrl);
                startRecordingService(rtspUrl, outputFolderUri);
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_RTSP_URL, rtspUrl).apply();
                isRecording = true;
                startRecordingButton.setText("Stop Recording");
            }
        });

        clearLogButton.setOnClickListener(v -> {
            logBuilder.setLength(0);
            logTextView.setText("Logs cleared...");
            addLog("Log cleared");
        });

        saveLogButton.setOnClickListener(v -> saveLogToFile());

        toggleLogButton.setOnClickListener(v -> {
            isLoggingEnabled = !isLoggingEnabled;
            addLog("Logging " + (isLoggingEnabled ? "ENABLED" : "DISABLED"));
            updateToggleLogButtonText();
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOGGING_ENABLED, isLoggingEnabled).apply();
        });
    }

    // Updates the text of the Log ON/OFF button
    @SuppressLint("SetTextI18n")
    private void updateToggleLogButtonText() {
        toggleLogButton.setText(isLoggingEnabled ? "Log: ON" : "Log: OFF");
    }

    // Appends message to log display if enabled
    private void addLog(String message) {
        final boolean isToggleMessage = message.startsWith("Logging ");
        if (!isLoggingEnabled && !isToggleMessage) return;

        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logBuilder.append("[").append(timestamp).append("] ").append(message).append("\n");
            logTextView.setText(logBuilder.toString());
            // Auto-scroll
            logTextView.post(() -> ((ScrollView) logTextView.getParent()).fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Saves current logs to a file
    private void saveLogToFile() {
        if (outputFolderUri == null) {
            Toast.makeText(this, "Please select output folder first", Toast.LENGTH_SHORT).show();
            addLog("ERROR: Cannot save log - no output folder selected");
            return;
        }

        boolean wasLoggingEnabled = isLoggingEnabled; // Store current state
        isLoggingEnabled = true; // Temporarily enable for saving message
        addLog("Saving log to file...");

        new Thread(() -> { // Perform file IO off the main thread
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                if (folder == null || !folder.exists() || !folder.isDirectory()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Output folder not accessible");
                    });
                    return;
                }

                String fileName = "recording_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                DocumentFile logFile = folder.createFile("text/plain", fileName);
                if (logFile == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to create log file", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Failed to create log file");
                    });
                    return;
                }

                try (OutputStream fos = getContentResolver().openOutputStream(logFile.getUri())) {
                    if (fos == null) throw new IOException("Failed to open output stream");
                    fos.write(logBuilder.toString().getBytes());
                    fos.flush();
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "Log saved: " + fileName, Toast.LENGTH_SHORT).show();
                    addLog("Log saved to: " + fileName);
                });

            } catch (Exception e) {
                addLog("ERROR saving log: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                isLoggingEnabled = wasLoggingEnabled; // Restore original state
            }
        }).start();
    }

    // Starts and binds to the RecordingService
    private void startRecordingService(String rtspUrl, Uri folderUri) {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        serviceIntent.putExtra("rtspUrl", rtspUrl);
        serviceIntent.putExtra("outputFolderUri", folderUri.toString());
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // Stops and unbinds from the RecordingService
    private void stopRecordingService() {
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        stopService(new Intent(this, RecordingService.class));
    }

    // Bind to service when activity starts
    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, RecordingService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // Unbind from service when activity stops
    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    // Callback interface for service -> activity logging
    public interface LogCallback { void log(String message); }

    // --- Background Recording Service ---
    public static class RecordingService extends Service {
        // Configuration
        private static final long SEGMENT_DURATION_MS = 3 * 60 * 1000; // 3 minutes
        private static final long RECONNECT_DELAY_SHORT_MS = 3000;
        private static final long RECONNECT_DELAY_LONG_MS = 10000;
        private static final long MIN_SEGMENT_SIZE_BYTES = 1024 * 100; // 100 KB
        private static final long CONNECTION_TIMEOUT_MS = 30000;
        private static final int MAX_CONSECUTIVE_FAILURES = 5;
        private static final String NOTIFICATION_CHANNEL_ID = "recording_channel";

        // Service components & state
        private LibVLC libVLC;
        private MediaPlayer mediaPlayer;
        private File tempFile;
        private Uri outputFolderUri;
        private String rtspUrl;
        private int segmentCounter = 0;
        private final IBinder binder = new RecordingBinder();
        private boolean isRecording = false;
        private boolean shouldBeRecording = false;
        private Handler segmentHandler, reconnectHandler, watchdogHandler;
        private Runnable segmentRunnable, watchdogRunnable;
        private LogCallback logCallback;
        private PowerManager.WakeLock wakeLock; // WakeLock reference
        private long segmentStartTime = 0;
        private int consecutiveFailures = 0;
        private boolean isConnecting = false;
        private ConnectionState connectionState = ConnectionState.DISCONNECTED;

        private enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

        // Binder for clients
        public class RecordingBinder extends Binder { RecordingService getService() { return RecordingService.this; } }

        // Setter for log callback
        public void setLogCallback(LogCallback callback) { this.logCallback = callback; }

        // Log helper, posts to main thread
        private void log(String message) {
            if (logCallback != null) {
                new Handler(getMainLooper()).post(() -> logCallback.log(message));
            }
        }

        // Service initialization
        @Override
        public void onCreate() {
            super.onCreate();
            acquireWakeLock(); // Acquire WakeLock
            initializeLibVLC(); // Initialize LibVLC
            // Initialize handlers
            segmentHandler = new Handler(getMainLooper());
            reconnectHandler = new Handler(getMainLooper());
            watchdogHandler = new Handler(getMainLooper());
            log("Recording service initialized");
        }

        // Acquire CPU WakeLock
        private void acquireWakeLock() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RTSPRecorder::WakeLockTag");
                if (!wakeLock.isHeld()) wakeLock.acquire();
                log("Partial WakeLock acquired");
            } else {
                log("ERROR: PowerManager null, cannot acquire WakeLock");
            }
        }

        // Initialize LibVLC instance
        private void initializeLibVLC() {
            ArrayList<String> options = new ArrayList<>();
            options.add("-vvv"); // Verbose logs
            options.add("--network-caching=1500"); // Reduced caching
            options.add("--rtsp-tcp");
            options.add("--file-caching=1500"); // Reduced caching
            // Add other necessary options here if needed
            try {
                libVLC = new LibVLC(this, options);
                mediaPlayer = new MediaPlayer(libVLC);
                log("LibVLC initialized");
            } catch (Exception e) {
                log("ERROR creating LibVLC: " + e.getMessage());
                // Handle critical failure?
            }
        }

        // Handles service start commands
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) { // Service restarted
                log("Service restarted by system");
                if (rtspUrl != null && outputFolderUri != null && shouldBeRecording) {
                    log("Attempting resume...");
                    scheduleReconnect(RECONNECT_DELAY_SHORT_MS);
                }
                return START_STICKY;
            }

            // Get data from intent
            rtspUrl = intent.getStringExtra("rtspUrl");
            String outputFolderUriString = intent.getStringExtra("outputFolderUri");
            if (rtspUrl == null || outputFolderUriString == null) {
                log("ERROR: Missing URL or folder URI");
                Toast.makeText(this, "Missing URL/Folder", Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            // Initialize state for this recording session
            outputFolderUri = Uri.parse(outputFolderUriString);
            segmentCounter = 0;
            shouldBeRecording = true;
            consecutiveFailures = 0;
            log("Service starting: " + rtspUrl);

            // Start foreground service
            createNotificationChannel();
            startForeground(1, buildNotification("Initializing..."));
            log("Foreground service started");

            startNewSegment(); // Begin recording
            return START_STICKY;
        }

        @Override public IBinder onBind(Intent intent) { return binder; } // Return binder

        // Service cleanup
        @Override
        public void onDestroy() {
            super.onDestroy();
            shouldBeRecording = false;
            isRecording = false;
            log("Service stopping...");
            releaseWakeLock(); // Release WakeLock
            cancelAllHandlers(); // Cancel timers
            releaseMediaPlayer(); // Stop player
            releaseLibVLC(); // Release LibVLC
            saveFinalSegment(); // Save last part
            log("Recording stopped. Segments saved: " + (segmentCounter + (tempFile != null && tempFile.exists() && tempFile.length() > MIN_SEGMENT_SIZE_BYTES ? 1 : 0)));
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
        }

        // Release CPU WakeLock
        private void releaseWakeLock() {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                log("Wake lock released");
            }
        }

        // Cancel all pending Handler callbacks
        private void cancelAllHandlers() {
            if (segmentHandler != null && segmentRunnable != null) segmentHandler.removeCallbacks(segmentRunnable);
            if (reconnectHandler != null) reconnectHandler.removeCallbacksAndMessages(null);
            if (watchdogHandler != null && watchdogRunnable != null) watchdogHandler.removeCallbacks(watchdogRunnable);
            segmentRunnable = null; // Clear runnable references
            watchdogRunnable = null;
        }

        // Safely stop and release MediaPlayer
        private void releaseMediaPlayer() {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception e) { log("ERROR releasing MediaPlayer: " + e.getMessage()); }
                mediaPlayer = null;
            }
        }

        // Safely release LibVLC
        private void releaseLibVLC() {
            if (libVLC != null) {
                try { libVLC.release(); } catch (Exception e) { log("ERROR releasing LibVLC: " + e.getMessage()); }
                libVLC = null;
            }
        }

        // Save the last segment if valid
        private void saveFinalSegment() {
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.length() > MIN_SEGMENT_SIZE_BYTES) {
                    log("Saving final segment...");
                    saveSegmentToFolder(tempFile, segmentCounter);
                } else {
                    log("Deleting incomplete final segment (size: " + tempFile.length() + ")");
                    if (!tempFile.delete()) log("WARN: Failed to delete final temp file");
                }
                tempFile = null;
            }
        }

        // Helper to run code on main thread
        private void runOnUiThread(Runnable action) { new Handler(getMainLooper()).post(action); }

        // Core logic to start recording a new segment
        @SuppressLint("SpellCheckingInspection")
        private void startNewSegment() {
            if (!shouldBeRecording || isConnecting) { // Check state
                log(!shouldBeRecording ? "Recording cancelled" : "Already connecting");
                return;
            }

            try {
                isConnecting = true;
                connectionState = ConnectionState.CONNECTING;
                log("Starting segment " + (segmentCounter + 1));
                updateNotification("Connecting...");

                // Stop/save previous segment if needed
                handlePreviousSegment();

                // Create new temp file
                createNewTempFile();

                // Setup LibVLC Media
                final Media media = new Media(libVLC, Uri.parse(rtspUrl));

                // *** MODIFIED SOUT CHAIN FOR STREAM COPY (NO TRANSCODING / LOW HEAT / NO AUDIO) ***
                String soutChain = ":sout=#file{dst='" + tempFile.getAbsolutePath() + "'}";
                media.addOption(soutChain);
                media.addOption(":sout-keep");
                // Removed: :no-sout-all, :sout-audio, :sout-video (less relevant for #file)

                // Setup event listener
                setupMediaPlayerEventListener();

                // Start playback/recording
                mediaPlayer.setMedia(media);
                media.release();
                log("Starting playback (stream copy)...");
                mediaPlayer.play();
                startConnectionWatchdog(); // Start connection timeout timer

            } catch (Exception e) {
                isConnecting = false;
                connectionState = ConnectionState.ERROR;
                log("ERROR starting segment: " + e.getMessage());
                handleConnectionLoss("Exception: " + e.getMessage());
            }
        }

        // Stops player and saves previous segment if valid
        private void handlePreviousSegment() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                try { mediaPlayer.stop(); log("Previous player stopped"); }
                catch (Exception e) { log("ERROR stopping player: " + e.getMessage()); }
            }
            // Save if temp file exists and is large enough
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.length() > MIN_SEGMENT_SIZE_BYTES) {
                    saveSegmentToFolder(tempFile, segmentCounter);
                    segmentCounter++;
                } else {
                    log("Deleting small previous segment (" + tempFile.length() + " bytes)");
                    if (!tempFile.delete()) log("WARN: Failed delete prev temp");
                }
                tempFile = null; // Reset ref
            }
        }

        // Creates a new temp file for the segment
        private void createNewTempFile() throws IOException {
            tempFile = File.createTempFile("rec_segment_" + segmentCounter + "_", ".mp4", getCacheDir());
            log("Created temp file: " + tempFile.getName());
        }

        // Sets up the listener for MediaPlayer events
        private void setupMediaPlayerEventListener() {
            mediaPlayer.setEventListener(event -> {
                switch (event.type) {
                    case MediaPlayer.Event.Opening:
                        connectionState = ConnectionState.CONNECTING;
                        log("Stream opening...");
                        updateNotification("Opening...");
                        break;
                    case MediaPlayer.Event.Playing:
                        isConnecting = false; connectionState = ConnectionState.CONNECTED;
                        consecutiveFailures = 0; segmentStartTime = System.currentTimeMillis();
                        isRecording = true;
                        cancelConnectionWatchdog(); cancelReconnect();
                        log("✓ Connected - Recording segment " + (segmentCounter + 1));
                        updateNotification("Recording segment " + (segmentCounter + 1));
                        runOnUiThread(()-> Toast.makeText(this, "Recording segment " + (segmentCounter + 1), Toast.LENGTH_SHORT).show());
                        scheduleNextSegment();
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (event.getBuffering() < 100) log("Buffering: " + String.format(Locale.US, "%.1f%%", event.getBuffering()));
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        log("ERROR: MediaPlayer error");
                        isConnecting = false; connectionState = ConnectionState.ERROR;
                        handleConnectionLoss("MediaPlayer error");
                        break;
                    case MediaPlayer.Event.EndReached:
                        log("Stream EndReached");
                        isConnecting = false; connectionState = ConnectionState.DISCONNECTED;
                        handleConnectionLoss("Stream ended");
                        break;
                    case MediaPlayer.Event.Stopped:
                        // Handle unexpected stops if we should be recording
                        if (shouldBeRecording && connectionState != ConnectionState.RECONNECTING && connectionState != ConnectionState.DISCONNECTED) {
                            log("Stream stopped unexpectedly (state: "+connectionState+")");
                            isConnecting = false; connectionState = ConnectionState.DISCONNECTED;
                            handleConnectionLoss("Stream stopped");
                        }
                        break;
                }
            });
        }

        // Schedules the start of the next segment
        private void scheduleNextSegment() {
            cancelExistingSegmentCallback();
            segmentRunnable = () -> {
                if (shouldBeRecording && mediaPlayer != null && mediaPlayer.isPlaying()) {
                    log("Segment duration reached, starting next...");
                    startNewSegment();
                } else if (shouldBeRecording) {
                    log("Segment timer fired but not playing, treating as loss.");
                    handleConnectionLoss("Not playing at segment end");
                }
            };
            segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);
        }

        // Cancels any pending segment timer
        private void cancelExistingSegmentCallback() {
            if (segmentRunnable != null) segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        // Starts the connection timeout timer
        private void startConnectionWatchdog() {
            cancelConnectionWatchdog();
            watchdogRunnable = () -> {
                if (connectionState == ConnectionState.CONNECTING) {
                    log("Connection timeout (" + (CONNECTION_TIMEOUT_MS / 1000) + "s)");
                    isConnecting = false;
                    handleConnectionLoss("Connection timeout");
                }
            };
            watchdogHandler.postDelayed(watchdogRunnable, CONNECTION_TIMEOUT_MS);
        }

        // Cancels the connection timeout timer
        private void cancelConnectionWatchdog() {
            if (watchdogRunnable != null) watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }

        // Handles stream loss: saves partial segment, schedules reconnect
        private void handleConnectionLoss(String reason) {
            if (!shouldBeRecording) return; // Ignore if stopped manually

            isConnecting = false; connectionState = ConnectionState.RECONNECTING;
            isRecording = false; consecutiveFailures++;
            log("⚠ Connection lost: " + reason + " (#" + consecutiveFailures + ")");

            cancelExistingSegmentCallback(); // Stop segment timer
            cancelConnectionWatchdog(); // Stop connection timer

            handlePartialSegmentSave(); // Save partial data if valid

            // Determine reconnect delay (short initially, longer after repeated failures)
            long delay = (consecutiveFailures <= MAX_CONSECUTIVE_FAILURES) ? RECONNECT_DELAY_SHORT_MS : RECONNECT_DELAY_LONG_MS;
            String delayMsg = (delay / 1000) + "s";
            log("Reconnecting in " + delayMsg + "...");
            updateNotification("Reconnecting in " + delayMsg + "...");
            scheduleReconnect(delay); // Schedule the attempt
        }

        // Saves partially recorded segment if it meets size/duration criteria
        private void handlePartialSegmentSave() {
            if (tempFile != null && tempFile.exists()) {
                long duration = (segmentStartTime > 0) ? (System.currentTimeMillis() - segmentStartTime) : 0;
                long size = tempFile.length();
                if (size > MIN_SEGMENT_SIZE_BYTES && duration > 10000) { // Require >10s recording
                    log("Saving partial segment (" + (size / 1024) + " KB, " + (duration / 1000) + "s)");
                    saveSegmentToFolder(tempFile, segmentCounter);
                    segmentCounter++;
                } else {
                    log("Discarding small partial segment (" + (size / 1024) + " KB, " + (duration / 1000) + "s)");
                    if (!tempFile.delete()) log("WARN: Failed discard partial temp");
                }
                tempFile = null; segmentStartTime = 0; // Reset state
            }
        }

        // Schedules a reconnect attempt
        private void scheduleReconnect(long delay) {
            cancelReconnect(); // Ensure only one reconnect is scheduled
            reconnectHandler.postDelayed(() -> {
                if (shouldBeRecording) {
                    log("Attempting reconnect #" + (consecutiveFailures + 1) + "...");
                    startNewSegment();
                }
            }, delay);
        }

        // Cancels pending reconnect attempts
        private void cancelReconnect() {
            if (reconnectHandler != null) reconnectHandler.removeCallbacksAndMessages(null);
        }

        // Saves a segment file to the designated output folder
        private void saveSegmentToFolder(File segmentFileToSave, int segmentNumberToSave) {
            log("Saving segment " + segmentNumberToSave + "...");
            new Thread(() -> { // File IO on background thread
                DocumentFile savedFile = null; // Track if file was created for cleanup on error
                try {
                    DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                    if (folder == null || !folder.exists() || !folder.isDirectory()) {
                        throw new IOException("Output folder not accessible");
                    }

                    String fileName = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_seg" + segmentNumberToSave + ".mp4";
                    savedFile = folder.createFile("video/mp4", fileName); // Store reference
                    if (savedFile == null) {
                        throw new IOException("Failed to create file: " + fileName);
                    }

                    long bytesCopied = 0;
                    try (FileInputStream fis = new FileInputStream(segmentFileToSave);
                         OutputStream fos = getContentResolver().openOutputStream(savedFile.getUri())) {
                        if (fos == null) throw new IOException("Failed to open output stream for target");
                        byte[] buffer = new byte[16384]; int read;
                        while ((read = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read); bytesCopied += read;
                        }
                        fos.flush();
                    } // Auto-close streams

                    final long finalSize = bytesCopied;
                    final String finalName = fileName;
                    runOnUiThread(() -> { // Show success on UI thread
                        String sizeStr = String.format(Locale.US, "%.2f MB", finalSize / 1024.0 / 1024.0);
                        Toast.makeText(this, "Segment " + segmentNumberToSave + " saved (" + sizeStr + ")", Toast.LENGTH_SHORT).show();
                        log("✓ Segment " + segmentNumberToSave + " saved: " + finalName + " (" + sizeStr + ")");
                    });

                    // Delete temp file *after* successful copy and UI update
                    if (!segmentFileToSave.delete()) log("WARN: Failed delete temp after save: " + segmentFileToSave.getName());
                    log("Temp file deleted for segment " + segmentNumberToSave);

                } catch (Exception e) {
                    log("ERROR saving segment " + segmentNumberToSave + ": " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(this, "Error saving segment " + segmentNumberToSave + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
                    // Cleanup: Delete the partially created file in SAF if it exists
                    if (savedFile != null && savedFile.exists()) {
                        if (savedFile.delete()) {
                            log("Deleted partially saved file: " + savedFile.getName());
                        } else {
                            log("WARN: Failed to delete partially saved file: " + savedFile.getName());
                        }
                    }
                    // Attempt to delete original temp file even on error
                    if (segmentFileToSave != null && segmentFileToSave.exists() && !segmentFileToSave.delete()) {
                        log("WARN: Failed delete temp after save error: " + segmentFileToSave.getName());
                    }
                }
            }).start();
        }

        // Builds the foreground service notification
        private Notification buildNotification(String text) {
            int icon = R.mipmap.ic_launcher; // Use app icon
            return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("RTSP Recorder")
                    .setContentText(text)
                    .setSmallIcon(icon)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true) // Make it persistent
                    .build();
        }

        // Updates the foreground service notification text
        private void updateNotification(String text) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildNotification(text)); // Use ID 1 to update
        }

        // Public method for activity to check recording state
        public boolean isRecording() { return isRecording; }

        // Creates Notification Channel for Android Oreo+
        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Recording Status",
                        NotificationManager.IMPORTANCE_LOW); // Low importance = no sound/vibration
                channel.setDescription("Shows RTSP recording status");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                    log("Notification channel created.");
                } else {
                    log("ERROR: NotificationManager null, cannot create channel.");
                }
            }
        }
    } // End RecordingService
} // End MainActivity