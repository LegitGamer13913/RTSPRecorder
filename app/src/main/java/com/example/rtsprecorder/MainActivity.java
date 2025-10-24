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
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private TextView outputFilePathTextView;

    private Uri outputFolderUri;

    private RecordingService recordingService;
    private boolean isBound = false;
    private boolean isRecording = false;

    private final ActivityResultLauncher<Intent> selectOutputFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                            outputFolderUri = result.getData().getData();
                            if (outputFolderUri != null) {
                                // Take persistable URI permission
                                getContentResolver().takePersistableUriPermission(
                                        outputFolderUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                );
                                outputFilePathTextView.setText("Folder: " + outputFolderUri.getLastPathSegment());
                            }
                        }
                    });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            RecordingService.RecordingBinder binder = (RecordingService.RecordingBinder) service;
            recordingService = binder.getService();
            isBound = true;
            if (recordingService.isRecording()) {
                isRecording = true;
                startRecordingButton.setText("Stop Recording");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            recordingService = null;
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

        selectOutputFolderButton.setText("Select Output Folder");

        selectOutputFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            selectOutputFolderLauncher.launch(intent);
        });

        startRecordingButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecordingService();
                isRecording = false;
                startRecordingButton.setText("Start Recording");
            } else {
                String rtspUrl = rtspUrlEditText.getText().toString().trim();
                if (rtspUrl.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter an RTSP URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (outputFolderUri == null) {
                    Toast.makeText(MainActivity.this, "Please select an output folder", Toast.LENGTH_SHORT).show();
                    return;
                }

                startRecordingService(rtspUrl, outputFolderUri);
                isRecording = true;
                startRecordingButton.setText("Stop Recording");
            }
        });
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

        public class RecordingBinder extends Binder {
            RecordingService getService() {
                return RecordingService.this;
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
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) {
                return START_NOT_STICKY;
            }

            rtspUrl = intent.getStringExtra("rtspUrl");
            String outputFolderUriString = intent.getStringExtra("outputFolderUri");

            if (rtspUrl == null || outputFolderUriString == null) {
                Toast.makeText(this, "Missing URL or output folder", Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            outputFolderUri = Uri.parse(outputFolderUriString);
            segmentCounter = 0;

            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, "recording_channel")
                    .setContentTitle("RTSP Recorder")
                    .setContentText("Recording in progress")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            startForeground(1, notification);

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

            // Cancel segment timer
            if (segmentHandler != null && segmentRunnable != null) {
                segmentHandler.removeCallbacks(segmentRunnable);
            }

            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }

            if (libVLC != null) {
                libVLC.release();
                libVLC = null;
            }

            // Save the last segment
            if (tempFile != null && tempFile.exists()) {
                saveSegmentToFolder(tempFile, segmentCounter);
            }

            Toast.makeText(this, "Recording stopped. " + (segmentCounter + 1) + " segments saved.", Toast.LENGTH_SHORT).show();
        }

        private void runOnUiThread(Runnable action) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(action);
        }

        private void startNewSegment() {
            try {
                // Stop current recording if exists
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();

                    // Save the previous segment
                    if (tempFile != null && tempFile.exists()) {
                        final File fileToSave = tempFile;
                        final int currentSegment = segmentCounter - 1;
                        saveSegmentToFolder(fileToSave, currentSegment);
                    }
                }

                // Create a new temporary file for this segment
                tempFile = new File(getCacheDir(), "temp_segment_" + segmentCounter + "_" + System.currentTimeMillis() + ".mp4");

                final Media media = new Media(libVLC, Uri.parse(rtspUrl));

                // Simple sout for recording only (no display)
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
                            stopSelf();
                            break;
                        case MediaPlayer.Event.EndReached:
                            Toast.makeText(this, "Stream ended", Toast.LENGTH_SHORT).show();
                            break;
                    }
                });

                mediaPlayer.setMedia(media);
                media.release();

                mediaPlayer.play();
                isRecording = true;

                updateNotification("Recording segment " + (segmentCounter + 1));
                Toast.makeText(this, "Recording segment " + (segmentCounter + 1), Toast.LENGTH_SHORT).show();

                // Schedule next segment
                segmentRunnable = () -> {
                    segmentCounter++;
                    startNewSegment();
                };
                segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                stopSelf();
            }
        }

        private void saveSegmentToFolder(File segmentFile, int segmentNumber) {
            new Thread(() -> {
                try {
                    // Create DocumentFile from folder URI
                    DocumentFile folder = DocumentFile.fromTreeUri(this, outputFolderUri);
                    if (folder == null || !folder.exists() || !folder.isDirectory()) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "Output folder not accessible", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    // Create new file in the folder
                    String fileName = "recording_segment_" + segmentNumber + "_" + System.currentTimeMillis() + ".mp4";
                    DocumentFile newFile = folder.createFile("video/mp4", fileName);

                    if (newFile == null) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "Failed to create file in folder", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    // Copy temp file to the new document file
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
                        runOnUiThread(() ->
                                Toast.makeText(this, "Segment " + segmentNumber + " saved (" + (finalSize / 1024 / 1024) + " MB)", Toast.LENGTH_SHORT).show()
                        );
                    }
                    fis.close();

                    // Delete temp file
                    segmentFile.delete();

                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(this, "Error saving segment " + segmentNumber + ": " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
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