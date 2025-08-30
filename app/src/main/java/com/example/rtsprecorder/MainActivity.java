package com.example.rtsprecorder;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private EditText rtspUrlEditText;
    private Button startRecordingButton;
    private VLCVideoLayout videoLayout;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rtspUrlEditText = findViewById(R.id.rtspUrl);
        startRecordingButton = findViewById(R.id.startRecording);
        videoLayout = findViewById(R.id.video_layout);

        ArrayList<String> options = new ArrayList<>();
        options.add("-vvv"); // For verbose logging
        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        startRecordingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String rtspUrl = rtspUrlEditText.getText().toString();
                if (rtspUrl.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter an RTSP URL", Toast.LENGTH_SHORT).show();
                    return;
                }

                startRecording(rtspUrl);
            }
        });
    }

    private void startRecording(String rtspUrl) {
        File outputFile = new File(getExternalFilesDir(null), "recording.mp4");

        final Media media = new Media(libVLC, Uri.parse(rtspUrl));
        media.addOption(":sout=#duplicate{dst=file{dst=" + outputFile.getAbsolutePath() + "},dst=display}");
        media.addOption(":sout-keep");

        mediaPlayer.setMedia(media);
        media.release();

        mediaPlayer.play();
        Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mediaPlayer.attachViews(videoLayout, null, false, false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mediaPlayer.stop();
        mediaPlayer.detachViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mediaPlayer.release();
        libVLC.release();
    }
}
