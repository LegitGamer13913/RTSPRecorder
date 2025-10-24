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
    private ScrollView logScrollView;
    private Button clearLogButton;
    private Button saveLogButton;

    private Uri outputFolderUri;
    private StringBuilder logBuilder = new StringBuilder();

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
                startRecordingButton.setText("Start Recording");
            } else {
                String rtspUrl = rtspUrlEditText.getText().toString().trim();
                if (rtspUrl.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter an RTSP URL", Toast.LENGTH_SHORT).show();
                    addLog("ERROR: RTSP URL is empty");
                    return;
                }
                if (outputFolderUri == null) {
                    Toast.makeText(MainActivity.this, "Please select an output folder", Toast.LENGTH_SHORT).show();
                    addLog("ERROR: Output folder not selected");
                    return;
                }

                addLog("Starting recording: " + rtspUrl);
                startRecordingService(rtspUrl, outputFolderUri);
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
    }

    private void addLog(String message) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String logEntry = "[" + timestamp + "] " + message + "\n";
            logBuilder.append(logEntry);
            logTextView.setText(logBuilder.toString());

            // Auto-scroll to bottom
            logTextView.post(() -> {
                ScrollView scrollView = (ScrollView) logTextView.getParent();
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            });
        });
    }

    private void saveLogToFile() {
        if (outputFolderUri == null) {
            Toast.makeText(this, "Please select output folder first", Toast.LENGTH_SHORT).show();
            addLog("ERROR: Cannot save log - no output folder selected");
            return;
        }

        new Thread(() -> {
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                if (folder == null || !folder.exists() || !folder.isDirectory()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Output folder not accessible");
                    });
                    return;
                }

                String fileName = "recording_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                DocumentFile logFile = folder.createFile("text/plain", fileName);

                if (logFile == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to create log file", Toast.LENGTH_SHORT).show();
                        addLog("ERROR: Failed to create log file");
                    });
                    return;
                }

                OutputStream fos = getContentResolver().openOutputStream(logFile.getUri());
                if (fos != null) {
                    fos.write(logBuilder.toString().getBytes());
                    fos.flush();
                    fos.close();

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Log saved: " + fileName, Toast.LENGTH_SHORT).show();
                        addLog("Log saved to: " + fileName);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    addLog("ERROR saving log: " + e.getMessage());
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
        private static final long SEGMENT_DURATION_MS = 3 * 60 * 1000; // 3 minutes

        private LibVLC libVLC;
        private MediaPlayer mediaPlayer;
        private File tempFile;
        private Uri outputFolderUri;
        private String rtspUrl;
        private int segmentCounter = 0;
        private final IBinder binder = new RecordingBinder();
        private boolean isRecording = false;
        private Handler segmentHandler;
        private Runnable segmentRunnable;
        private LogCallback logCallback;

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
                logCallback.log(message);
            }
        }

        @Override
        public void onCreate() {
            super.onCreate();
            ArrayList<String> options = new ArrayList<>();
            options.add("-vvv");
            options.add("--network-caching=1000");
            options.add("--rtsp-tcp");
            options.add("--file-caching=2000");
            libVLC = new LibVLC(this, options);
            mediaPlayer = new MediaPlayer(libVLC);
            isRecording = false;
            segmentHandler = new Handler(getMainLooper());
            log("Recording service created");
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) {
                log("ERROR: Service started with null intent");
                return START_NOT_STICKY;
            }

            rtspUrl = intent.getStringExtra("rtspUrl");
            String outputFolderUriString = intent.getStringExtra("outputFolderUri");

            if (rtspUrl == null || outputFolderUriString == null) {
                Toast.makeText(this, "Missing URL or output folder", Toast.LENGTH_SHORT).show();
                log("ERROR: Missing URL or output folder");
                stopSelf();
                return START_NOT_STICKY;
            }

            outputFolderUri = Uri.parse(outputFolderUriString);
            segmentCounter = 0;

            log("Service starting with URL: " + rtspUrl);

            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, "recording_channel")
                    .setContentTitle("RTSP Recorder")
                    .setContentText("Recording in progress")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            startForeground(1, notification);
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
            isRecording = false;

            log("Service stopping...");

            if (segmentHandler != null && segmentRunnable != null) {
                segmentHandler.removeCallbacks(segmentRunnable);
            }

            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                    log("MediaPlayer stopped");
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }

            if (libVLC != null) {
                libVLC.release();
                libVLC = null;
            }

            if (tempFile != null && tempFile.exists()) {
                saveSegmentToFolder(tempFile, segmentCounter);
            }

            Toast.makeText(this, "Recording stopped. " + (segmentCounter + 1) + " segments saved.", Toast.LENGTH_SHORT).show();
            log("Recording stopped. Total segments: " + (segmentCounter + 1));
        }

        private void runOnUiThread(Runnable action) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(action);
        }

        private void startNewSegment() {
            try {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                    log("Previous segment stopped");

                    if (tempFile != null && tempFile.exists()) {
                        final File fileToSave = tempFile;
                        final int currentSegment = segmentCounter - 1;
                        saveSegmentToFolder(fileToSave, currentSegment);
                    }
                }

                tempFile = new File(getCacheDir(), "temp_segment_" + segmentCounter + "_" + System.currentTimeMillis() + ".mp4");
                log("Created temp file: " + tempFile.getName());

                final Media media = new Media(libVLC, Uri.parse(rtspUrl));

                String soutChain = ":sout=#transcode{vcodec=h264,acodec=mp4a}:std{access=file,mux=mp4,dst='" + tempFile.getAbsolutePath() + "'}";

                media.addOption(soutChain);
                media.addOption(":sout-keep");
                media.addOption(":no-sout-all");
                media.addOption(":sout-audio");
                media.addOption(":sout-video");

                mediaPlayer.setEventListener(event -> {
                    switch (event.type) {
                        case MediaPlayer.Event.EncounteredError:
                            Toast.makeText(this, "Recording error", Toast.LENGTH_SHORT).show();
                            log("ERROR: MediaPlayer encountered error");
                            stopSelf();
                            break;
                        case MediaPlayer.Event.EndReached:
                            Toast.makeText(this, "Stream ended", Toast.LENGTH_SHORT).show();
                            log("Stream ended");
                            break;
                        case MediaPlayer.Event.Playing:
                            log("Playback started for segment " + (segmentCounter + 1));
                            break;
                        case MediaPlayer.Event.Buffering:
                            log("Buffering: " + event.getBuffering() + "%");
                            break;
                    }
                });

                mediaPlayer.setMedia(media);
                media.release();

                mediaPlayer.play();
                isRecording = true;

                updateNotification("Recording segment " + (segmentCounter + 1));
                Toast.makeText(this, "Recording segment " + (segmentCounter + 1), Toast.LENGTH_SHORT).show();
                log("Started recording segment " + (segmentCounter + 1));

                segmentRunnable = () -> {
                    segmentCounter++;
                    log("Segment timer triggered, starting next segment");
                    startNewSegment();
                };
                segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                log("ERROR starting segment: " + e.getMessage());
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                stopSelf();
            }
        }

        private void saveSegmentToFolder(File segmentFile, int segmentNumber) {
            log("Saving segment " + segmentNumber + "...");
            new Thread(() -> {
                try {
                    DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                    if (folder == null || !folder.exists() || !folder.isDirectory()) {
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show();
                            log("ERROR: Output folder not accessible");
                        });
                        return;
                    }

                    String fileName = "recording_segment_" + segmentNumber + "_" + System.currentTimeMillis() + ".mp4";
                    DocumentFile newFile = folder.createFile("video/mp4", fileName);

                    if (newFile == null) {
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Failed to create file in folder", Toast.LENGTH_SHORT).show();
                            log("ERROR: Failed to create file " + fileName);
                        });
                        return;
                    }

                    FileInputStream fis = new FileInputStream(segmentFile);
                    OutputStream fos = getContentResolver().openOutputStream(newFile.getUri());

                    if (fos != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalBytes = 0;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                        }
                        fos.flush();
                        fos.close();

                        final long finalSize = totalBytes;
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Segment " + segmentNumber + " saved (" + (finalSize / 1024 / 1024) + " MB)", Toast.LENGTH_SHORT).show();
                            log("Segment " + segmentNumber + " saved: " + fileName + " (" + (finalSize / 1024 / 1024) + " MB)");
                        });
                    }
                    fis.close();

                    segmentFile.delete();
                    log("Temp file deleted for segment " + segmentNumber);

                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error saving segment " + segmentNumber + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                        log("ERROR saving segment " + segmentNumber + ": " + e.getMessage());
                    });
                }
            }).start();
        }

        private void updateNotification(String text) {
            Notification notification = new NotificationCompat.Builder(this, "recording_channel")
                    .setContentTitle("RTSP Recorder")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(1, notification);
            }
        }

        public MediaPlayer getMediaPlayer() {
            return mediaPlayer;
        }

        public boolean isRecording() {
            return isRecording;
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel serviceChannel = new NotificationChannel(
                        "recording_channel",
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