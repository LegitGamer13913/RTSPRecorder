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
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private VLCVideoLayout videoLayout;
    private TextView outputFilePathTextView;

    private Uri outputFileUri;

    private RecordingService recordingService;
    private boolean isBound = false;
    private boolean isRecording = false;

    private final ActivityResultLauncher<Intent> selectOutputFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                            outputFileUri = result.getData().getData();
                            if (outputFileUri != null) {
                                outputFilePathTextView.setText(outputFileUri.getPath());
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
                MediaPlayer mediaPlayer = recordingService.getMediaPlayer();
                if (mediaPlayer != null) {
                    mediaPlayer.attachViews(videoLayout, null, false, false);
                }
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
        videoLayout = findViewById(R.id.video_layout);
        Button selectOutputFileButton = findViewById(R.id.selectOutputFile);
        outputFilePathTextView = findViewById(R.id.outputFilePath);

        selectOutputFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/mp4");
            intent.putExtra(Intent.EXTRA_TITLE, "recording.mp4");
            selectOutputFileLauncher.launch(intent);
        });

        startRecordingButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecordingService();
                isRecording = false;
                startRecordingButton.setText("Start Recording");
            } else {
                String rtspUrl = rtspUrlEditText.getText().toString();
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
        serviceIntent.putExtra("outputFileUri", outputFileUri);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopRecordingService() {
        if (isBound) {
            if (recordingService != null && recordingService.getMediaPlayer() != null) {
                recordingService.getMediaPlayer().detachViews();
            }
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
            if (recordingService != null && recordingService.getMediaPlayer() != null) {
                recordingService.getMediaPlayer().detachViews();
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    public static class RecordingService extends Service {
        private LibVLC libVLC;
        private MediaPlayer mediaPlayer;
        private ParcelFileDescriptor pfd;
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
            libVLC = new LibVLC(this, options);
            mediaPlayer = new MediaPlayer(libVLC);
            isRecording = false;
        }

        @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String rtspUrl = intent.getStringExtra("rtspUrl");
            Uri outputFileUri = intent.getParcelableExtra("outputFileUri", Uri.class);

            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, "recording_channel")
                    .setContentTitle("RTSP Recorder")
                    .setContentText("Recording in progress.")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();

            startForeground(1, notification);

            try {
                assert outputFileUri != null;
                pfd = getContentResolver().openFileDescriptor(outputFileUri, "w");
                if (pfd == null) {
                    stopSelf();
                    return START_NOT_STICKY;
                }

                final Media media = new Media(libVLC, Uri.parse(rtspUrl));
                media.addOption(":sout=#duplicate{dst=fd{fd=" + pfd.getFd() + "},dst=display}");
                media.addOption(":sout-keep");

                mediaPlayer.setMedia(media);
                media.release();

                mediaPlayer.play();
                isRecording = true;
                Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();

            } catch (IOException e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
                Toast.makeText(this, "Error starting recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.detachViews();
                mediaPlayer.release();
            }

            try {
                if (pfd != null) {
                    pfd.close();
                }
            } catch (IOException e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
            }

            if (libVLC != null) {
                libVLC.release();
            }

            isRecording = false;
            Toast.makeText(this, "Recording stopped.", Toast.LENGTH_SHORT).show();
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
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
