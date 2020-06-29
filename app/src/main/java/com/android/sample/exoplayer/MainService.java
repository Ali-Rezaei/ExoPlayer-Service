package com.android.sample.exoplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.android.sample.exoplayer.MainUtil.isServiceRunning;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_PERIOD_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;

public class MainService extends Service implements ExoPlayer.EventListener {

    private static final String TAG = MainService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 1;
    private static final long MAX_POSITION_FOR_SEEK_TO_PREVIOUS = 3000;
    private static final String EXTRA_INTERFACE = "interface";
    static final String STR_RECEIVER_ACTIVITY = "com.MainService.receiver.activity";
    static final String STR_RECEIVER_SERVICE = "com.MainService.receiver.service";
    static final String SAMPLE = "sample";
    static final String IS_PLAYING = "isPlaying";
    private SimpleExoPlayer mExoPlayer;
    private static MediaSessionCompat mMediaSession;
    private PlaybackStateCompat.Builder mStateBuilder;
    private MediaMetadataCompat.Builder mMetadataBuilder;
    private List<Sample> mSamples = new ArrayList<>();
    private NotificationManager mNotificationManager;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean isPlaying = intent.getBooleanExtra(IS_PLAYING, false);
            mExoPlayer.setPlayWhenReady(isPlaying);
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();

        // Initialize the Media Session.
        initializeMediaSession();

        // Initialize the player.
        initializePlayer();

        registerReceiver(mBroadcastReceiver, new IntentFilter(STR_RECEIVER_SERVICE));

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MainServiceBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand()");
        if (intent.hasExtra(EXTRA_INTERFACE) && intent.hasExtra(Intent.EXTRA_INTENT)) {
            IMediaButtons iMediaButtons = (IMediaButtons) Objects.requireNonNull(intent.getSerializableExtra(EXTRA_INTERFACE));
            Intent myIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            iMediaButtons.execute(myIntent);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Initializes the Media Session to be enabled with media buttons, transport controls, callbacks
     * and media controller.
     */
    private void initializeMediaSession() {

        // Create a MediaSessionCompat.
        mMediaSession = new MediaSessionCompat(this, TAG);

        // Enable callbacks from MediaButtons and TransportControls.
        // noinspection deprecation
        mMediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // Do not let MediaButtons restart the player when the app is not visible.
        mMediaSession.setMediaButtonReceiver(null);

        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player.
        mStateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SEEK_TO);

        mMediaSession.setPlaybackState(mStateBuilder.build());

        mMetadataBuilder = new MediaMetadataCompat.Builder();
        mMediaSession.setMetadata(mMetadataBuilder.build());

        // MySessionCallback has methods that handle callbacks from a media controller.
        mMediaSession.setCallback(new MySessionCallback());

        // Start the Media Session since the activity is active.
        mMediaSession.setActive(true);
    }

    /**
     * Shows Media Style notification, with an action that depends on the current MediaSession
     * PlaybackState.
     *
     * @param state  The PlaybackState of the MediaSession.
     * @param sample The Sample object to display title and composer on Notification.
     */
    private void showNotification(PlaybackStateCompat state, Sample sample) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getString(R.string.notification_channel_id));

        int icon;
        String play_pause;
        if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
            icon = R.drawable.exo_controls_pause;
            play_pause = getString(R.string.pause);
        } else {
            icon = R.drawable.exo_controls_play;
            play_pause = getString(R.string.play);
        }

        NotificationCompat.Action playPauseAction = new NotificationCompat.Action(
                icon, play_pause,
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE));

        NotificationCompat.Action restartAction = new NotificationCompat
                .Action(R.drawable.exo_controls_previous, getString(R.string.restart),
                MediaButtonReceiver.buildMediaButtonPendingIntent
                        (this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));

        NotificationCompat.Action nextAction = new NotificationCompat
                .Action(R.drawable.exo_controls_next, getString(R.string.next),
                MediaButtonReceiver.buildMediaButtonPendingIntent
                        (this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));

        PendingIntent contentPendingIntent = PendingIntent.getActivity
                (this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

        Intent intent = new Intent(this, StopServiceBroadcastReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this.getApplicationContext(), 0, intent, 0);

        Bitmap largeImage = ((BitmapDrawable) Sample.getComposerArtBySampleID(
                this,
                sample.getSampleID())).getBitmap();

        builder.setContentTitle(sample.getTitle())
                .setContentText(sample.getComposer())
                .setContentIntent(contentPendingIntent)
                .setDeleteIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_music_note)
                .setLargeIcon(largeImage)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(restartAction)
                .addAction(playPauseAction)
                .addAction(nextAction).setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mMediaSession.getSessionToken())
                .setShowActionsInCompactView(1, 2));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = getString(R.string.notification_channel_id);
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            mNotificationManager.createNotificationChannel(channel);
            builder.setChannelId(channelId);
        }

        Notification notificationCompat = builder.build();
        if (state.getState() == PlaybackStateCompat.STATE_PAUSED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            mNotificationManager.notify(NOTIFICATION_ID, notificationCompat);
        } else {
            startForeground(NOTIFICATION_ID, notificationCompat);
        }
    }

    /**
     * Initialize ExoPlayer.
     */
    private void initializePlayer() {
        if (mExoPlayer == null) {
            // Create an instance of the ExoPlayer.
            mExoPlayer = new SimpleExoPlayer.Builder(this).build();

            List<Integer> sampleIDs = Sample.getAllSampleIDs(this);
            MediaSource[] mediaSourcesToLoad = new MediaSource[sampleIDs.size()];

            for (int i = 0; i < sampleIDs.size(); i++) {
                Sample sample = Sample.getSampleByID(this, sampleIDs.get(i));
                if (sample == null) {
                    Toast.makeText(this, getString(R.string.sample_not_found_error),
                            Toast.LENGTH_SHORT).show();
                } else {
                    mSamples.add(sample);
                    // Prepare the MediaSource.
                    String userAgent = Util.getUserAgent(this, "ExoPlayer");
                    MediaSource mediaSource = new ProgressiveMediaSource.Factory(new DefaultDataSourceFactory(
                            this, userAgent)).createMediaSource(Uri.parse(sample.getUri()));
                    mediaSourcesToLoad[i] = mediaSource;
                }
            }
            mExoPlayer.prepare(new ConcatenatingMediaSource(mediaSourcesToLoad));

            MainPosition mainPosition = Storage.getInstance(this).getPosition();
            if (mainPosition == null) {
                mExoPlayer.setPlayWhenReady(true);
            } else {
                mExoPlayer.seekTo(mainPosition.getCurrentWindowIndex(), mainPosition.getCurrentPosition());
                mExoPlayer.setPlayWhenReady(false);
            }

            // Set the ExoPlayer.EventListener to this service.
            mExoPlayer.addListener(this);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved()");
        super.onTaskRemoved(rootIntent);
        if (!mExoPlayer.getPlayWhenReady()) {
            mNotificationManager.cancel(NOTIFICATION_ID);
            stopSelf();
        }
    }

    /**
     * Release ExoPlayer.
     */
    private void releasePlayer() {
        mExoPlayer.removeListener(this);
        mExoPlayer.stop();
        mExoPlayer.release();
        mExoPlayer = null;
    }

    /**
     * Release the player when the service is destroyed.
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();
        MainPosition mainPosition = new MainPosition(mExoPlayer.getCurrentWindowIndex(),
                mExoPlayer.getCurrentPosition());
        Storage.getInstance(this).storePosition(mainPosition);
        releasePlayer();
        unregisterReceiver(mBroadcastReceiver);
        mHandler.removeCallbacksAndMessages(null);
        mMediaSession.setActive(false);
    }

    // ExoPlayer Event Listeners

    /**
     * Method that is called when the ExoPlayer state changes. Used to update the MediaSession
     * PlayBackState to keep in sync.
     *
     * @param playWhenReady true if ExoPlayer is playing, false if it's paused.
     * @param playbackState int describing the state of ExoPlayer. Can be STATE_READY, STATE_IDLE,
     *                      STATE_BUFFERING, or STATE_ENDED.
     */
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        Log.d(TAG, "onPlayerStateChanged()");
        if (playbackState == ExoPlayer.STATE_READY && playWhenReady) {
            mStateBuilder.setState(PlaybackStateCompat.STATE_PLAYING,
                    mExoPlayer.getCurrentPosition(), 1f);
            mExoPlayer.setPlayWhenReady(true);
        } else if (playbackState == ExoPlayer.STATE_READY) {
            mStateBuilder.setState(PlaybackStateCompat.STATE_PAUSED,
                    mExoPlayer.getCurrentPosition(), 1f);
        }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        Log.d(TAG, "onPositionDiscontinuity()");
        if (reason == DISCONTINUITY_REASON_PERIOD_TRANSITION ||
                reason == DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            mExoPlayer.setPlayWhenReady(true);
            updateNotificationAndDrawable();
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        Log.d(TAG, "onIsPlayingChanged()");
        updateNotificationAndDrawable();
    }

    private void updateNotificationAndDrawable() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mMetadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mExoPlayer.getDuration());
                mMediaSession.setMetadata(mMetadataBuilder.build());
            }
        }, 50);
        mMediaSession.setPlaybackState(mStateBuilder.build());
        Sample sample = mSamples.get(mExoPlayer.getCurrentWindowIndex());
        showNotification(mStateBuilder.build(), sample);
        updateDrawable(sample);
    }

    private void updateDrawable(Sample sample) {
        Intent intent = new Intent(STR_RECEIVER_ACTIVITY);
        intent.putExtra(SAMPLE, sample);
        intent.putExtra(IS_PLAYING, mExoPlayer.getPlayWhenReady());
        sendBroadcast(intent);
    }

    /**
     * Media Session Callbacks, where all external clients control the player.
     */
    private class MySessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            mExoPlayer.setPlayWhenReady(true);
        }

        @Override
        public void onPause() {
            mExoPlayer.setPlayWhenReady(false);
        }

        @Override
        public void onSkipToPrevious() {
            if (mExoPlayer.getCurrentPosition() <= MAX_POSITION_FOR_SEEK_TO_PREVIOUS) {
                mExoPlayer.previous();
            } else {
                mExoPlayer.seekTo(0);
            }
        }

        @Override
        public void onSkipToNext() {
            mExoPlayer.next();
        }

        @Override
        public void onSeekTo(long pos) {
            mExoPlayer.seekTo(pos);
        }
    }

    /**
     * This class will be what is returned when an activity binds to this service.
     * The activity will also use this to know what it can get from our service to know
     * about the video playback.
     */
    class MainServiceBinder extends Binder {

        /**
         * This method should be used only for setting the exoplayer instance.
         * If exoplayer's internal are altered or accessed we can not guarantee
         * things will work correctly.
         */
        SimpleExoPlayer getExoPlayerInstance() {
            return mExoPlayer;
        }

        Sample getSample() {
            return mSamples.get(mExoPlayer.getCurrentWindowIndex());
        }
    }

    /**
     * Broadcast Receiver registered to receive the MEDIA_BUTTON intent coming from clients.
     */
    public static class MediaReceiver extends BroadcastReceiver implements IMediaButtons {

        public MediaReceiver() {
        }

        @Override
        public void onReceive(Context context, final Intent intent) {
            Log.d(TAG, "MediaReceiver$onReceive()");
            if (isServiceRunning(MainService.class, context)) {
                execute(intent);
            } else {
                Intent myIntent = new Intent(context, MainService.class);
                myIntent.putExtra(EXTRA_INTERFACE, this);
                myIntent.putExtra(Intent.EXTRA_INTENT, intent);
                context.startService(myIntent);
            }
        }

        @Override
        public void execute(Intent intent) {
            MediaButtonReceiver.handleIntent(mMediaSession, intent);
        }
    }

    public static class StopServiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "StopServiceBroadcastReceiver$onReceive()");
            Intent stopIntent = new Intent(context, MainService.class);
            context.stopService(stopIntent);
        }
    }

    public interface IMediaButtons extends Serializable {
        void execute(Intent intent);
    }
}
