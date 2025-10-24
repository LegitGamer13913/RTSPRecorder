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
import android.os.IBinder;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private TextView outputFilePathTextView;

    private Uri outputFileUri;
    private String outputFilePath;

    private RecordingService recordingService;
    private boolean isBound = false;
    private boolean isRecording = false;

    private final ActivityResultLauncher<Intent> selectOutputFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                            outputFileUri = result.getData().getData();
                            if (outputFileUri != null) {
                                // Take persistable URI permission
                                getContentResolver().takePersistableUriPermission(
                                        outputFileUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                );
                                outputFilePathTextView.setText(outputFileUri.toString());
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
        Button selectOutputFileButton = findViewById(R.id.selectOutputFile);
        outputFilePathTextView = findViewById(R.id.outputFilePath);

        selectOutputFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/mp4");
            intent.putExtra(Intent.EXTRA_TITLE, "recording_" + System.currentTimeMillis() + ".mp4");
            selectOutputFileLauncher.launch(intent);
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
                if (outputFileUri == null) {
                    Toast.makeText(MainActivity.this, "Please select an output file", Toast.LENGTH_SHORT).show();
                    return;
                }

                startRecordingService(rtspUrl, outputFileUri);
                isRecording = true;
                startRecordingButton.setText("Stop Recording");
            }
        });
    }

    private void startRecordingService(String rtspUrl, Uri outputFileUri) {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        serviceIntent.putExtra("rtspUrl", rtspUrl);
        serviceIntent.putExtra("outputFileUri", outputFileUri.toString());
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
        private LibVLC libVLC;
        private MediaPlayer mediaPlayer;
        private File tempFile;
        private Uri finalOutputUri;
        private final IBinder binder = new RecordingBinder();
        private boolean isRecording = false;

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
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) {
                return START_NOT_STICKY;
            }

            String rtspUrl = intent.getStringExtra("rtspUrl");
            String outputFileUriString = intent.getStringExtra("outputFileUri");

            if (rtspUrl == null || outputFileUriString == null) {
                Toast.makeText(this, "Missing URL or output file", Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            finalOutputUri = Uri.parse(outputFileUriString);

            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, "recording_channel")
                    .setContentTitle("RTSP Recorder")
                    .setContentText("Recording in progress")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            startForeground(1, notification);

            try {
                // Create a temporary file in cache directory
                tempFile = new File(getCacheDir(), "temp_recording_" + System.currentTimeMillis() + ".mp4");

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
                Toast.makeText(this, "Recording started to: " + tempFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                stopSelf();
                return START_NOT_STICKY;
            }

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

            // Copy temp file to final destination
            if (tempFile != null && tempFile.exists() && finalOutputUri != null) {
                new Thread(() -> {
                    try {
                        FileInputStream fis = new FileInputStream(tempFile);
                        FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(finalOutputUri);

                        if (fos != null) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                            fos.flush();
                            fos.close();
                        }
                        fis.close();

                        // Delete temp file
                        tempFile.delete();

                        runOnUiThread(() ->
                                Toast.makeText(this, "Recording saved successfully", Toast.LENGTH_LONG).show()
                        );
                    } catch (IOException e) {
                        e.printStackTrace();
                        runOnUiThread(() ->
                                Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show()
                        );
                    }
                }).start();
            }

            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
        }

        private void runOnUiThread(Runnable action) {
            android.os.Handler handler = new android.os.Handler(getMainLooper());
            handler.post(action);
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