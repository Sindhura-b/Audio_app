package com.codingwithmitch.audiostreamer.players;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

public class MediaPlayerAdapter extends PlayerAdapter {

    private static final String TAG = "MediaPlayerAdapter";

    private final Context mContext;
    private MediaMetadataCompat mCurrentMedia;
    private boolean mCurrentMediaPlayedToCompletion;
    private int mState;
    private long mStartTime;
    private PlaybackInfoListener mPlaybackInfoListener;

    private SimpleExoPlayer mExoPlayer;
    private TrackSelector mTrackSelector;
    private DefaultRenderersFactory mRenderer;
    private DataSource.Factory mDataSourceFactory;
    private ExoPlayerEventListener mExoPlayerEventListener;

    public MediaPlayerAdapter(@NonNull Context context, PlaybackInfoListener playbackInfoListener) {
        super(context);
        mPlaybackInfoListener = playbackInfoListener;
        mContext = context.getApplicationContext();
    }

    private void initializeExoPlayer(){
        if(mExoPlayer==null){
            mTrackSelector = new DefaultTrackSelector();
            mRenderer = new DefaultRenderersFactory(mContext);
            mDataSourceFactory = new DefaultDataSourceFactory(mContext, Util.getUserAgent(mContext, "AudioStreamer"));
            mExoPlayer = ExoPlayerFactory.newSimpleInstance(mRenderer, mTrackSelector, new DefaultLoadControl());
            Log.d(TAG, "ExoPlayer Initialized");
            //mExoPlayer = SimpleExoPlayer.Builder(mContext).build();
            if(mExoPlayerEventListener==null){
                mExoPlayerEventListener = new ExoPlayerEventListener();
            }
            mExoPlayer.addListener(mExoPlayerEventListener);
        }
    }

    private void release(){
        if(mExoPlayer!=null){
            mExoPlayer.release();
            mExoPlayer=null;
        }
    }

    @Override
    protected void onPlay() {
        if(mExoPlayer != null && !mExoPlayer.getPlayWhenReady()){
            mExoPlayer.setPlayWhenReady(true);
            Log.d(TAG, "state is playing");
            setNewState(PlaybackStateCompat.STATE_PLAYING);
        }

    }

    @Override
    protected void onPause() {
        if(mExoPlayer != null && mExoPlayer.getPlayWhenReady()){
            mExoPlayer.setPlayWhenReady(false);
            Log.d(TAG, "state is paused");
            setNewState(PlaybackStateCompat.STATE_PAUSED);
        }
    }

    @Override
    public void playFromMedia(MediaMetadataCompat metadata) {
        startTrackingPlayback();
        playFile(metadata);
    }

    @Override
    public MediaMetadataCompat getCurrentMedia() {
        return mCurrentMedia;
    }

    @Override
    public boolean isPlaying() {
        if(mExoPlayer!=null){
            return mExoPlayer.getPlayWhenReady();
        }
        return false;
    }

    @Override
    protected void onStop() {
        setNewState(PlaybackStateCompat.STATE_STOPPED);
        release();
    }

    @Override
    public void seekTo(long position) {
        if(mExoPlayer!=null){
            mExoPlayer.seekTo((int) position);
            setNewState(mState);
        }
    }

    @Override
    public void setVolume(float volume) {
        if(mExoPlayer!=null){
            mExoPlayer.setVolume(volume);
        }
    }

    private void playFile(MediaMetadataCompat metadata) {
        String mediaId = metadata.getDescription().getMediaId();
        boolean mediaChanged = (mCurrentMedia==null) || !mediaId.equals(mCurrentMedia.getDescription().getMediaId());

        if(mCurrentMediaPlayedToCompletion){
            mediaChanged=true;
            mCurrentMediaPlayedToCompletion=false;
        }

        if(!mediaChanged){
            if(!isPlaying()){
                play();
            }
            return;
        }
        else{
            release();
        }

        mCurrentMedia = metadata;

        initializeExoPlayer();

        try{
            MediaSource audioSource = new ExtractorMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(mCurrentMedia.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI)));
            mExoPlayer.prepare(audioSource);
            Log.d(TAG, "onPlayerStateChanged: PREPARE");
        }catch(Exception e){
            throw new RuntimeException("Failed to play media url: "+ mCurrentMedia.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI), e);
        }

        play();
    }

    private void startTrackingPlayback() {
        final Handler handler = new Handler();
        final Runnable runnable = new Runnable(){
            @Override
            public void run() {
                if(isPlaying()){
                    mPlaybackInfoListener.seekTo(
                            mExoPlayer.getContentPosition(), mExoPlayer.getDuration()
                    );
                    handler.postDelayed(this, 100);
                    //Log.d(TAG, "getting content position" + mExoPlayer.getContentPosition());
                }
                if(mExoPlayer.getContentPosition() >= mExoPlayer.getDuration()
                        && mExoPlayer.getDuration() > 0){
                    mPlaybackInfoListener.onPlaybackComplete();
                }
            }
        };
        handler.postDelayed(runnable, 100);
    }

    private void setNewState(@PlaybackStateCompat.State int newPlayerState){
        mState = newPlayerState;

        if(mState==PlaybackStateCompat.STATE_STOPPED){
            mCurrentMediaPlayedToCompletion=true;
        }

        final long reportPosition = mExoPlayer == null ? 0 : mExoPlayer.getCurrentPosition();
        //send playback state information to service
        publishStateBuilder(reportPosition);
    }

    private void publishStateBuilder(long reportPosition){
        final PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
        stateBuilder.setActions(getAvailableActions());
        stateBuilder.setState(mState,
                reportPosition,
                1.0f,
                SystemClock.elapsedRealtime());
        Log.d(TAG, "publish state builder");
        mPlaybackInfoListener.onPlaybackStateChange(stateBuilder.build());
        mPlaybackInfoListener.updateUI(mCurrentMedia.getDescription().getMediaId());
    }

    private class ExoPlayerEventListener implements Player.EventListener{

        @Override
        public void onTimelineChanged(Timeline timeline, @Nullable Object manifest, int reason) {

        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

        }

        @Override
        public void onLoadingChanged(boolean isLoading) {

        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            switch(playbackState){

                case Player.STATE_ENDED:{
                    setNewState(PlaybackStateCompat.STATE_PAUSED);
                    break;
                }
                case Player.STATE_BUFFERING:{
                    mStartTime = System.currentTimeMillis();
                    break;
                }
                case Player.STATE_IDLE:{
                    break;
                }
                case Player.STATE_READY:{
                    Log.d(TAG, "onPlayerStateChanged: TIME ELAPSED:" + (System.currentTimeMillis() - mStartTime));
                    break;
                }
            }
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {

        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {

        }

        @Override
        public void onPositionDiscontinuity(int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

        }

        @Override
        public void onSeekProcessed() {

        }
    }

    /**
     * Set the current capabilities available on this session. Note: If a capability is not
     * listed in the bitmask of capabilities then the MediaSession will not handle it. For
     * example, if you don't want ACTION_STOP to be handled by the MediaSession, then don't
     * included it in the bitmask that's returned.
     */
    @PlaybackStateCompat.Actions
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        switch (mState) {
            case PlaybackStateCompat.STATE_STOPPED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE;
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO;
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_STOP;
                break;
            default:
                actions |= PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PAUSE;
        }
        return actions;
    }

}
