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
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
// import android.os.PowerManager; // WAKELOCK WAS REMOVED
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
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private TextView outputFilePathTextView;
    private TextView logTextView;
    // private ScrollView logScrollView; // REMOVED: Never used
    private Button clearLogButton;
    private Button saveLogButton;

    private Uri outputFolderUri;
    // 'logBuilder' can be final
    private final StringBuilder logBuilder = new StringBuilder();

    private RecordingService recordingService;
    private boolean isBound = false;
    private boolean isRecording = false;

    private final ActivityResultLauncher<Intent> selectOutputFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                            outputFolderUri = result.getData().getData();
                            if (outputFolderUri != null) {
                                getContentResolver().takePersistableUriPermission(
                                        outputFolderUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                );
                                // TODO: Use string resource R.string.folder_label
                                outputFilePathTextView.setText("Folder: " + outputFolderUri.getLastPathSegment());
                                addLog("Output folder selected: " + outputFolderUri.getLastPathSegment());
                            }
                        }
                    });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            RecordingService.RecordingBinder binder = (RecordingService.RecordingBinder) service;
            recordingService = binder.getService();
            recordingService.setLogCallback(MainActivity.this::addLog);
            isBound = true;
            if (recordingService.isRecording()) {
                isRecording = true;
                // TODO: Use string resource R.string.stop_recording
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

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rtspUrlEditText = findViewById(R.id.rtspUrl);
        startRecordingButton = findViewById(R.id.startRecording);
        Button selectOutputFolderButton = findViewById(R.id.selectOutputFile);
        outputFilePathTextView = findViewById(R.id.outputFilePath);
        logTextView = findViewById(R.id.logTextView);
        clearLogButton = findViewById(R.id.clearLogButton);
        saveLogButton = findViewById(R.id.saveLogButton);

        // TODO: Use string resource R.string.select_output_folder
        selectOutputFolderButton.setText("Select Output Folder");

        addLog("Application started");

        selectOutputFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            selectOutputFolderLauncher.launch(intent);
            addLog("Opening folder picker...");
        });

        startRecordingButton.setOnClickListener(v -> {
            if (isRecording) {
                addLog("Stopping recording...");
                stopRecordingService();
                isRecording = false;
                // TODO: Use string resource R.string.start_recording
                startRecordingButton.setText("Start Recording");
            } else {
                String rtspUrl = rtspUrlEditText.getText().toString().trim();
                if (rtspUrl.isEmpty()) {
                    // TODO: Use string resource
                    Toast.makeText(MainActivity.this, "Please enter an RTSP URL", Toast.LENGTH_SHORT).show();
                    addLog("ERROR: RTSP URL is empty");
                    return;
                }
                if (outputFolderUri == null) {
                    // TODO: Use string resource
                    Toast.makeText(MainActivity.this, "Please select an output folder", Toast.LENGTH_SHORT).show();
                    addLog("ERROR: Output folder not selected");
                    return;
                }

                addLog("Starting recording: " + rtspUrl);
                startRecordingService(rtspUrl, outputFolderUri);
                isRecording = true;
                // TODO: Use string resource R.string.stop_recording
                startRecordingButton.setText("Stop Recording");
            }
        });

        clearLogButton.setOnClickListener(v -> {
            logBuilder.setLength(0);
            // TODO: Use string resource R.string.logs_cleared
            logTextView.setText("Logs cleared...");
            addLog("Log cleared");
        });

        saveLogButton.setOnClickListener(v -> saveLogToFile());
    }

    private void addLog(String message) {
        runOnUiThread(() -> {
            // Use Locale.US for consistent timestamp formatting
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String logEntry = "[" + timestamp + "] " + message + "\n";
            logBuilder.append(logEntry);
            logTextView.setText(logBuilder.toString());

            logTextView.post(() -> {
                // Find ScrollView dynamically
                ScrollView scrollView = (ScrollView) logTextView.getParent();
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            });
        });
    }

    private void saveLogToFile() {
        if (outputFolderUri == null) {
            // TODO: Use string resource
            Toast.makeText(this, "Please select output folder first", Toast.LENGTH_SHORT).show();
            addLog("ERROR: Cannot save log - no output folder selected");
            return;
        }

        new Thread(() -> {
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                if (folder == null || !folder.exists() || !folder.isDirectory()) {
                    runOnUiThread(() -> {
                        // TODO: Use string resource
                        Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Output folder not accessible");
                    });
                    return;
                }

                // Use Locale.US for consistent file naming
                String fileName = "recording_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                DocumentFile logFile = folder.createFile("text/plain", fileName);

                if (logFile == null) {
                    runOnUiThread(() -> {
                        // TODO: Use string resource
                        Toast.makeText(this, "Failed to create log file", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Failed to create log file");
                    });
                    return;
                }

                try (OutputStream fos = getContentResolver().openOutputStream(logFile.getUri())) {
                    if (fos != null) {
                        fos.write(logBuilder.toString().getBytes());
                        fos.flush();
                    }
                } // try-with-resources handles closing fos

                runOnUiThread(() -> {
                    // TODO: Use string resource
                    Toast.makeText(this, "Log saved: " + fileName, Toast.LENGTH_SHORT).show();
                    addLog("Log saved to: " + fileName);
                });

            } catch (Exception e) {
                // Replaced printStackTrace with log
                addLog("ERROR saving log: " + e.getMessage());
                runOnUiThread(() -> {
                    // TODO: Use string resource
                    Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startRecordingService(String rtspUrl, Uri outputFolderUri) {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        serviceIntent.putExtra("rtspUrl", rtspUrl);
        serviceIntent.putExtra("outputFolderUri", outputFolderUri.toString());
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopRecordingService() {
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        Intent serviceIntent = new Intent(this, RecordingService.class);
        stopService(serviceIntent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, RecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    public interface LogCallback {
        void log(String message);
    }

    public static class RecordingService extends Service {
        private static final long SEGMENT_DURATION_MS = 1 * 60 * 1000; // 1 minute
        private static final long RECONNECT_DELAY_SHORT_MS = 3000; // 3 seconds
        private static final long RECONNECT_DELAY_LONG_MS = 10000; // 10 seconds
        private static final long MIN_SEGMENT_SIZE_BYTES = 1024 * 100; // 100 KB minimum
        private static final long CONNECTION_TIMEOUT_MS = 30000; // 30 seconds
        private static final int MAX_CONSECUTIVE_FAILURES = 5;

        private static final String NOTIFICATION_CHANNEL_ID = "recording_channel";

        private LibVLC libVLC;
        private MediaPlayer mediaPlayer;
        private File tempFile;
        private Uri outputFolderUri;
        private String rtspUrl;
        private int segmentCounter = 0;
        private final IBinder binder = new RecordingBinder();
        private boolean isRecording = false;
        private boolean shouldBeRecording = false;
        private Handler segmentHandler;
        private Runnable segmentRunnable;
        private Handler reconnectHandler;
        private Handler watchdogHandler;
        private Runnable watchdogRunnable;
        private LogCallback logCallback;
        // WAKELOCK WAS REMOVED

        private long segmentStartTime = 0;
        // REMOVED: 'lastSuccessfulConnection' was assigned but never accessed
        // private long lastSuccessfulConnection = 0;
        private int consecutiveFailures = 0;
        private boolean isConnecting = false;

        private enum ConnectionState {
            DISCONNECTED,
            CONNECTING,
            CONNECTED,
            RECONNECTING,
            ERROR
        }

        private ConnectionState connectionState = ConnectionState.DISCONNECTED;

        public class RecordingBinder extends Binder {
            RecordingService getService() {
                return RecordingService.this;
            }
        }

        public void setLogCallback(LogCallback callback) {
            this.logCallback = callback;
        }

        private void log(String message) {
            if (logCallback != null) {
                // Run on main thread to avoid concurrency issues with UI
                Handler handler = new Handler(getMainLooper());
                handler.post(() -> logCallback.log(message));
            }
        }

        @Override
        public void onCreate() {
            super.onCreate();

            // WAKELOCK WAS REMOVED

            ArrayList<String> options = new ArrayList<>();
            options.add("-vvv");
            options.add("--network-caching=3000");
            options.add("--rtsp-tcp");
            options.add("--file-caching=3000");
            options.add("--rtsp-frame-buffer-size=500000");
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");

            try {
                libVLC = new LibVLC(this, options);
                mediaPlayer = new MediaPlayer(libVLC);
                log("Recording service created with LibVLC");
            } catch (Exception e) {
                // Replaced printStackTrace with log
                log("ERROR: Failed to create LibVLC: " + e.getMessage());
            }

            isRecording = false;
            shouldBeRecording = false;
            segmentHandler = new Handler(getMainLooper());
            reconnectHandler = new Handler(getMainLooper());
            watchdogHandler = new Handler(getMainLooper());

            log("Recording service initialized");
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) {
                log("Service restarted by system after crash");
                if (rtspUrl != null && outputFolderUri != null && shouldBeRecording) {
                    log("Attempting to resume recording...");
                    scheduleReconnect(RECONNECT_DELAY_SHORT_MS);
                }
                return START_STICKY;
            }

            rtspUrl = intent.getStringExtra("rtspUrl");
            String outputFolderUriString = intent.getStringExtra("outputFolderUri");

            if (rtspUrl == null || outputFolderUriString == null) {
                // TODO: Use string resource
                Toast.makeText(this, "Missing URL or output folder", Toast.LENGTH_SHORT).show();
                log("ERROR: Missing URL or output folder");
                stopSelf();
                return START_NOT_STICKY;
            }

            outputFolderUri = Uri.parse(outputFolderUriString);
            segmentCounter = 0;
            shouldBeRecording = true;
            consecutiveFailures = 0;

            log("Service starting with URL: " + rtspUrl);

            createNotificationChannel();
            // TODO: Use string resource
            startForeground(1, buildNotification("Initializing..."));
            log("Foreground service started");

            startNewSegment();

            return START_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return binder;
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            shouldBeRecording = false;
            isRecording = false;

            log("Service stopping...");

            // WAKELOCK WAS REMOVED

            // Cancel all handlers
            if (segmentHandler != null && segmentRunnable != null) {
                segmentHandler.removeCallbacks(segmentRunnable);
            }
            if (reconnectHandler != null) {
                reconnectHandler.removeCallbacksAndMessages(null);
            }
            if (watchdogHandler != null && watchdogRunnable != null) {
                watchdogHandler.removeCallbacks(watchdogRunnable);
            }

            // Stop media player
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                        log("MediaPlayer stopped");
                    }
                    mediaPlayer.release();
                    mediaPlayer = null;
                } catch (Exception e) {
                    log("ERROR releasing MediaPlayer: " + e.getMessage());
                }
            }

            if (libVLC != null) {
                try {
                    libVLC.release();
                    libVLC = null;
                } catch (Exception e) {
                    log("ERROR releasing LibVLC: " + e.getMessage());
                }
            }

            // Refactored logic to reduce duplication
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.length() > MIN_SEGMENT_SIZE_BYTES) {
                    log("Saving final segment...");
                    saveSegmentToFolder(tempFile, segmentCounter);
                } else {
                    log("Deleted incomplete final segment");
                    if (!tempFile.delete()) {
                        log("WARN: Failed to delete incomplete final segment.");
                    }
                }
            }

            // TODO: Use string resource
            Toast.makeText(this, "Recording stopped. " + (segmentCounter + 1) + " segments saved.", Toast.LENGTH_SHORT).show();
            log("Recording stopped. Total segments: " + (segmentCounter + 1));
        }

        private void runOnUiThread(Runnable action) {
            Handler handler = new Handler(getMainLooper());
            handler.post(action);
        }

        // Suppress false "typo" warnings for VLC options
        @SuppressLint("SpellCheckingInspection")
        private void startNewSegment() {
            if (!shouldBeRecording) {
                log("Recording cancelled, not starting new segment");
                return;
            }

            if (isConnecting) {
                log("Already attempting connection, skipping duplicate request");
                return;
            }

            try {
                isConnecting = true;
                connectionState = ConnectionState.CONNECTING;
                log("Starting new segment " + (segmentCounter + 1));
                // TODO: Use string resource
                updateNotification("Connecting to stream...");

                // Stop and save previous segment
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    try {
                        mediaPlayer.stop();
                        log("Previous MediaPlayer stopped");
                    } catch (Exception e) {
                        log("ERROR stopping MediaPlayer: " + e.getMessage());
                    }

                    if (tempFile != null && tempFile.exists()) {
                        if (tempFile.length() > MIN_SEGMENT_SIZE_BYTES) {
                            final File fileToSave = tempFile;
                            final int currentSegment = segmentCounter;
                            saveSegmentToFolder(fileToSave, currentSegment);
                            segmentCounter++;
                        } else {
                            log("Deleting incomplete segment (size: " + tempFile.length() + " bytes)");
                            if (!tempFile.delete()) {
                                log("WARN: Failed to delete incomplete segment");
                            }
                        }
                    }
                }

                // Create new temp file
                tempFile = new File(getCacheDir(), "temp_segment_" + segmentCounter + "_" + System.currentTimeMillis() + ".mp4");
                log("Created temp file: " + tempFile.getName());

                // Create media
                final Media media = new Media(libVLC, Uri.parse(rtspUrl));

                // These are NOT typos. They are LibVLC options.
                String soutChain = ":sout=#transcode{vcodec=h264,vb=2000,acodec=mp4a,ab=128}:std{access=file,mux=mp4,dst='" + tempFile.getAbsolutePath() + "'}";

                media.addOption(soutChain);
                media.addOption(":sout-keep");
                media.addOption(":no-sout-all");
                media.addOption(":sout-audio");
                media.addOption(":sout-video");

                // Set event listener
                mediaPlayer.setEventListener(event -> {
                    switch (event.type) {
                        case MediaPlayer.Event.Opening:
                            connectionState = ConnectionState.CONNECTING;
                            log("Stream opening...");
                            // TODO: Use string resource
                            updateNotification("Opening stream...");
                            startConnectionWatchdog();
                            break;

                        case MediaPlayer.Event.Playing:
                            isConnecting = false;
                            connectionState = ConnectionState.CONNECTED;
                            consecutiveFailures = 0;
                            // REMOVED: 'lastSuccessfulConnection' was not used
                            // lastSuccessfulConnection = System.currentTimeMillis();
                            segmentStartTime = System.currentTimeMillis();
                            isRecording = true;

                            cancelConnectionWatchdog();
                            cancelReconnect();

                            log("✓ Successfully connected - Recording segment " + (segmentCounter + 1));
                            // TODO: Use string resource
                            updateNotification("Recording segment " + (segmentCounter + 1));
                            // TODO: Use string resource
                            Toast.makeText(this, "Recording segment " + (segmentCounter + 1), Toast.LENGTH_SHORT).show();

                            // Schedule next segment
                            scheduleNextSegment();
                            break;

                        case MediaPlayer.Event.Buffering:
                            float buffering = event.getBuffering();
                            if (buffering < 100) {
                                // Use Locale.US for consistent number formatting
                                log("Buffering: " + String.format(Locale.US, "%.1f", buffering) + "%");
                            }
                            break;

                        case MediaPlayer.Event.EncounteredError:
                            isConnecting = false;
                            connectionState = ConnectionState.ERROR;
                            log("ERROR: MediaPlayer encountered error");
                            handleConnectionLoss("MediaPlayer error");
                            break;

                        case MediaPlayer.Event.EndReached:
                            isConnecting = false;
                            connectionState = ConnectionState.DISCONNECTED;
                            log("Stream ended (EndReached)");
                            handleConnectionLoss("Stream ended");
                            break;

                        case MediaPlayer.Event.Stopped:
                            if (shouldBeRecording && connectionState != ConnectionState.RECONNECTING) {
                                isConnecting = false;
                                log("Stream stopped unexpectedly");
                                handleConnectionLoss("Stream stopped");
                            }
                            break;
                    }
                });

                mediaPlayer.setMedia(media);
                media.release();

                log("Starting playback...");
                mediaPlayer.play();

            } catch (Exception e) {
                isConnecting = false;
                connectionState = ConnectionState.ERROR;
                // Replaced printStackTrace with log
                log("ERROR starting segment: " + e.getMessage());
                handleConnectionLoss("Exception: " + e.getMessage());
            }
        }

        private void scheduleNextSegment() {
            if (segmentRunnable != null) {
                segmentHandler.removeCallbacks(segmentRunnable);
            }

            segmentRunnable = () -> {
                if (shouldBeRecording && mediaPlayer != null && mediaPlayer.isPlaying()) {
                    log("Segment duration reached, starting next segment");
                    startNewSegment();
                }
            };
            segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);
        }

        private void startConnectionWatchdog() {
            cancelConnectionWatchdog();

            watchdogRunnable = () -> {
                if (connectionState == ConnectionState.CONNECTING) {
                    log("Connection timeout - stream failed to connect within " + (CONNECTION_TIMEOUT_MS / 1000) + " seconds");
                    isConnecting = false;
                    handleConnectionLoss("Connection timeout");
                }
            };
            watchdogHandler.postDelayed(watchdogRunnable, CONNECTION_TIMEOUT_MS);
        }

        private void cancelConnectionWatchdog() {
            if (watchdogHandler != null && watchdogRunnable != null) {
                watchdogHandler.removeCallbacks(watchdogRunnable);
            }
        }

        private void handleConnectionLoss(String reason) {
            if (!shouldBeRecording) {
                return;
            }

            connectionState = ConnectionState.RECONNECTING;
            isRecording = false;
            consecutiveFailures++;

            log("⚠ Connection lost: " + reason + " (failure #" + consecutiveFailures + ")");

            // Cancel segment timer
            if (segmentRunnable != null) {
                segmentHandler.removeCallbacks(segmentRunnable);
            }

            cancelConnectionWatchdog();

            // Refactored logic to reduce duplication
            if (tempFile != null && tempFile.exists()) {
                long recordedDuration = System.currentTimeMillis() - segmentStartTime;
                long fileSize = tempFile.length();

                if (fileSize > MIN_SEGMENT_SIZE_BYTES && recordedDuration > 10000) {
                    log("Saving partial segment (" + (fileSize / 1024) + " KB, " + (recordedDuration / 1000) + " seconds)");
                    final File fileToSave = tempFile;
                    final int currentSegment = segmentCounter;
                    saveSegmentToFolder(fileToSave, currentSegment);
                    segmentCounter++;
                } else {
                    log("Discarding incomplete segment (" + (fileSize / 1024) + " KB, " + (recordedDuration / 1000) + " seconds)");
                    if (!tempFile.delete()) {
                        log("WARN: Failed to delete incomplete segment");
                    }
                }
                tempFile = null;
            }


            // Determine reconnect delay based on failure history
            long reconnectDelay = (consecutiveFailures <= MAX_CONSECUTIVE_FAILURES)
                    ? RECONNECT_DELAY_SHORT_MS
                    : RECONNECT_DELAY_LONG_MS;

            String delayMsg = (reconnectDelay == RECONNECT_DELAY_SHORT_MS) ? "3s" : "10s";
            log("Will attempt reconnection in " + delayMsg + "...");
            // TODO: Use string resource
            updateNotification("Reconnecting in " + delayMsg + "...");

            scheduleReconnect(reconnectDelay);
        }

        private void scheduleReconnect(long delay) {
            cancelReconnect();

            reconnectHandler.postDelayed(() -> {
                if (shouldBeRecording) {
                    log("Attempting reconnection (attempt " + (consecutiveFailures + 1) + ")...");
                    startNewSegment();
                }
            }, delay);
        }

        private void cancelReconnect() {
            if (reconnectHandler != null) {
                reconnectHandler.removeCallbacksAndMessages(null);
            }
        }

        private void saveSegmentToFolder(File segmentFile, int segmentNumber) {
            log("Saving segment " + segmentNumber + "...");
            new Thread(() -> {
                try {
                    DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                    if (folder == null || !folder.exists() || !folder.isDirectory()) {
                        runOnUiThread(() -> {
                            // TODO: Use string resource
                            Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show();
                            log("ERROR: Output folder not accessible");
                        });
                        return;
                    }

                    // Use Locale.US for consistent file naming
                    String fileName = "recording_segment_" + segmentNumber + "_" + System.currentTimeMillis() + ".mp4";
                    DocumentFile newFile = folder.createFile("video/mp4", fileName);

                    if (newFile == null) {
                        runOnUiThread(() -> {
                            // TODO: Use string resource
                            Toast.makeText(this, "Failed to create file in folder", Toast.LENGTH_SHORT).show();
                            log("ERROR: Failed to create file " + fileName);
                        });
                        return;
                    }

                    long totalBytes = 0;
                    try (FileInputStream fis = new FileInputStream(segmentFile);
                         OutputStream fos = getContentResolver().openOutputStream(newFile.getUri())) {

                        if (fos != null) {
                            byte[] buffer = new byte[16384]; // 16KB buffer
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                                totalBytes += bytesRead;
                            }
                            fos.flush();
                        }
                    } // try-with-resources handles closing fis and fos

                    final long finalSize = totalBytes;
                    runOnUiThread(() -> {
                        // Use Locale.US for consistent number formatting
                        String sizeStr = String.format(Locale.US, "%.2f MB", finalSize / 1024.0 / 1024.0);
                        // TODO: Use string resource
                        Toast.makeText(this, "Segment " + segmentNumber + " saved (" + sizeStr + ")", Toast.LENGTH_SHORT).show();
                        log("✓ Segment " + segmentNumber + " saved: " + fileName + " (" + sizeStr + ")");
                    });

                    if (!segmentFile.delete()) {
                        log("WARN: Failed to delete temp file: " + segmentFile.getName());
                    }
                    log("Temp file deleted for segment " + segmentNumber);

                } catch (Exception e) {
                    // Replaced printStackTrace with log
                    log("ERROR saving segment " + segmentNumber + ": " + e.getMessage());
                    runOnUiThread(() -> {
                        // TODO: Use string resource
                        Toast.makeText(this, "Error saving segment " + segmentNumber + ": ".concat(e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }

        private Notification buildNotification(String text) {
            // TODO: Use string resource
            return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("RTSP Recorder")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();
        }

        private void updateNotification(String text) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(1, buildNotification(text));
            }
        }

        public boolean isRecording() {
            return isRecording;
        }

        // REMOVED: getMediaPlayer() was not used
        // public MediaPlayer getMediaPlayer() {
        //     return mediaPlayer;
        // }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // TODO: Use string resource
                NotificationChannel serviceChannel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Recording Channel",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(serviceChannel);
                }
            }
        }
    }
}