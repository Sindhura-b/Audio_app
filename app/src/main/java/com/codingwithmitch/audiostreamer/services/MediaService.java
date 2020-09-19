package com.codingwithmitch.audiostreamer.services;

import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.codingwithmitch.audiostreamer.MyApplication;
import com.codingwithmitch.audiostreamer.R;
import com.codingwithmitch.audiostreamer.notifications.MediaNotificationManager;
import com.codingwithmitch.audiostreamer.players.MediaPlayerAdapter;
import com.codingwithmitch.audiostreamer.players.PlaybackInfoListener;
import com.codingwithmitch.audiostreamer.util.MediaLibrary;
import com.codingwithmitch.audiostreamer.util.MyPreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.codingwithmitch.audiostreamer.util.Constants.MEDIA_QUEUE_POSITION;
import static com.codingwithmitch.audiostreamer.util.Constants.QUEUE_NEW_PLAYLIST;
import static com.codingwithmitch.audiostreamer.util.Constants.SEEK_BAR_MAX;
import static com.codingwithmitch.audiostreamer.util.Constants.SEEK_BAR_PROGRESS;

public class MediaService extends MediaBrowserServiceCompat {

    private static final String TAG = "MediaService";

    private MediaSessionCompat mSession;
    private MediaPlayerAdapter mPlayback;
    private MyApplication mMyApplication;
    private MyPreferenceManager mMyPrefManager;
    private MediaNotificationManager mMediaNotificationManager;
    private boolean mIsServiceStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        mMyApplication = MyApplication.getInstance();
        mMyPrefManager = new MyPreferenceManager(this);

        mSession = new MediaSessionCompat(this, TAG);

        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);

        mSession.setCallback(new MediaSessionCallback());

        setSessionToken(mSession.getSessionToken());

        mPlayback = new MediaPlayerAdapter(this, new MediaPlayerListener());
        mMediaNotificationManager = new MediaNotificationManager(this);
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: stopped");
        super.onTaskRemoved(rootIntent);
        mPlayback.stop();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mSession.release();
        Log.d(TAG, "onDestroy: MediaPlayerAdapter Stopped, and MediaSession released");
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String s, int i, @Nullable Bundle bundle) {

        if(s.equals(getApplicationContext().getPackageName())){
            //allowed to browse media
            return new BrowserRoot("some_real_playlist", null);
        }
        return new BrowserRoot("empty_media", null);
    }

    @Override
    public void onLoadChildren(@NonNull String s, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {

        Log.d(TAG, "OnLoadChildren: called:" + s + "," + result);
        if(TextUtils.equals("empty_media", s)){
            result.sendResult(null);
            return;
        }

        result.sendResult(mMyApplication.getMediaItems());
    }

    public class MediaSessionCallback extends MediaSessionCompat.Callback{

        private final List<MediaSessionCompat.QueueItem> mPlaylist = new ArrayList<>();
        private int mQueueIndex = -1;
        private MediaMetadataCompat mPreparedMedia;

        @Override
        public void onPrepare() {
            if(mQueueIndex<0 && mPlaylist.isEmpty()){
                return;
            }

            String mediaId = mPlaylist.get(mQueueIndex).getDescription().getMediaId();
            mPreparedMedia = mMyApplication.getMediaItem(mediaId);
            mSession.setMetadata(mPreparedMedia);

            if(!mSession.isActive()){
                mSession.setActive(true);
            }
        }

        @Override
        public void onPlay() {
            if(!isReadyToPlay()){
                return;
            }
            if(mPreparedMedia == null){
                onPrepare();
            }

            mPlayback.playFromMedia(mPreparedMedia);

            mMyPrefManager.saveQueuePosition(mQueueIndex);
            mMyPrefManager.saveLastPlayedMedia(mPreparedMedia.getDescription().getMediaId());
        }

        private void resetPlaylist(){
            mPlaylist.clear();
            mQueueIndex = -1;
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(TAG, "onPlayFromMediaId: Called");
            //super.onPlayFromMediaId(mediaId, extras);
            if(extras.getBoolean(QUEUE_NEW_PLAYLIST, false)){
                resetPlaylist();
            }
            mPreparedMedia = mMyApplication.getMediaItem(mediaId);
            mSession.setMetadata(mPreparedMedia);
            if(!mSession.isActive()){
                mSession.setActive(true);
                Log.d(TAG, "Session is active");
            }
            mPlayback.playFromMedia(mPreparedMedia);

            int newQueuePosition =extras.getInt(MEDIA_QUEUE_POSITION, -1);
            if(newQueuePosition==-1){
                mQueueIndex++;
            }
            else{
                mQueueIndex=extras.getInt(MEDIA_QUEUE_POSITION);
            }

            mMyPrefManager.saveQueuePosition(mQueueIndex);
            mMyPrefManager.saveLastPlayedMedia(mPreparedMedia.getDescription().getMediaId());
        }

        @Override
        public void onPause() {
            mPlayback.pause();
        }

        @Override
        public void onSkipToNext() {

            Log.d(TAG, "onSkipToNext: SKIP TO NEXT");
            // increment and then check using modulus
            mQueueIndex = (++mQueueIndex % mPlaylist.size());
            mPreparedMedia = null;
            onPlay();
        }

        @Override
        public void onSkipToPrevious() {

            Log.d(TAG, "onSkipToPrevious: SKIP TO PREVIOUS");
            mQueueIndex = mQueueIndex > 0 ? mQueueIndex - 1 : mPlaylist.size() - 1;
            mPreparedMedia = null;
            onPlay();
        }

        @Override
        public void onStop() {
            mPlayback.stop();
            mSession.setActive(false);
        }

        @Override
        public void onSeekTo(long pos) {
            mPlayback.seekTo(pos);
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            Log.d(TAG, "onAddQueueItem: CALLED: position in list: " + mPlaylist.size());
            mPlaylist.add(new MediaSessionCompat.QueueItem(description, description.hashCode()));
            mQueueIndex = (mQueueIndex == -1) ? 0 : mQueueIndex;
            mSession.setQueue(mPlaylist);
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            mPlaylist.remove(new MediaSessionCompat.QueueItem(description, description.hashCode()));
            mQueueIndex = (mPlaylist.isEmpty()) ? -1 : mQueueIndex;
            mSession.setQueue(mPlaylist);
        }

        private boolean isReadyToPlay(){
            return(!mPlaylist.isEmpty());
        }
    }

    public class MediaPlayerListener implements PlaybackInfoListener {

        private final ServiceManager mServiceManager;

        MediaPlayerListener() {
            mServiceManager = new ServiceManager();
        }

        @Override
        public void onPlaybackStateChange(PlaybackStateCompat state) {
            // Report the state to the MediaSession.
            Log.d(TAG, "Play back state changed" + state);
            mSession.setPlaybackState(state);

            switch (state.getState()){
                case PlaybackStateCompat.STATE_PLAYING:
                    mServiceManager.updateNotification(state, mPlayback.getCurrentMedia().getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI));
                    Log.d(TAG, "Display notification:playing");
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    mServiceManager.updateNotification(state, mPlayback.getCurrentMedia().getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI));
                    Log.d(TAG, "Display notification:paused");
                    break;
                case PlaybackStateCompat.STATE_STOPPED:
                    mServiceManager.moveServiceOutOfStartedState();
                    Log.d(TAG, "Display notification:stopped");
                    break;
            }
        }

        @Override
        public void seekTo(long progress, long max) {
            Intent intent = new Intent();
            intent.setAction(getString(R.string.broadcast_seekbar_update));
            intent.putExtra(SEEK_BAR_PROGRESS, progress);
            intent.putExtra(SEEK_BAR_MAX, max);
            sendBroadcast(intent);

        }

        @Override
        public void onPlaybackComplete() {
            Log.d(TAG, "onPlaybackComplete: SKIPPING TO NEXT");
            mSession.getController().getTransportControls().skipToNext();
        }

        @Override
        public void updateUI(String mediaId) {
            Intent intent = new Intent();
            intent.setAction(getString(R.string.broadcast_update_ui));
            intent.putExtra(getString(R.string.broadcast_new_media_id), mediaId);
            sendBroadcast(intent);

        }

        class ServiceManager implements ICallback{
            //private PlaybackStateCompat mState;
            private String mDisplayImageUri;
            private Bitmap mCurrentArtistBitmap;
            private PlaybackStateCompat mState;
            private GetArtistBitmapAsyncTask mAsyncTask;

            public ServiceManager(){
            }

            public void updateNotification(PlaybackStateCompat state, String displayImageUri){
                mState = state;
                if(!displayImageUri.equals(mDisplayImageUri)){
                    mAsyncTask = new GetArtistBitmapAsyncTask(Glide.with(MediaService.this).asBitmap().load(displayImageUri).listener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            return false;
                        }
                    }).submit(),this);

                    mAsyncTask.execute();
                    mDisplayImageUri = displayImageUri;
                }
                else{
                    displayNotification(mCurrentArtistBitmap);
                }
            }

            private void displayNotification(Bitmap bitmap){
                Notification notification = null;


                switch (mState.getState()){

                    case PlaybackStateCompat.STATE_PLAYING:

                        notification = mMediaNotificationManager.buildNotification(mState, getSessionToken(),
                                mPlayback.getCurrentMedia().getDescription(), bitmap);

                        if(!mIsServiceStarted){
                            ContextCompat.startForegroundService(MediaService.this, new Intent(MediaService.this, MediaService.class));
                            mIsServiceStarted = true;
                        }
                        startForeground(MediaNotificationManager.NOTIFICATION_ID, notification);
                        break;

                    case PlaybackStateCompat.STATE_PAUSED:

                        stopForeground(false);

                        notification = mMediaNotificationManager.buildNotification(mState, getSessionToken(),
                                mPlayback.getCurrentMedia().getDescription(), bitmap);

                        mMediaNotificationManager.getNotificationManager().notify(MediaNotificationManager.NOTIFICATION_ID, notification);
                        break;

                }
            }
            private void moveServiceOutOfStartedState(){
                stopForeground(true);
                stopSelf();
                mIsServiceStarted = false;
            }

            @Override
            public void done(Bitmap bitmap) {
                mCurrentArtistBitmap = bitmap;
                displayNotification(mCurrentArtistBitmap);
            }
        }
    }

    static class GetArtistBitmapAsyncTask extends AsyncTask<Void, Void, Bitmap> {

        private FutureTarget<Bitmap> mBitmap;
        private ICallback mICallback;

        public GetArtistBitmapAsyncTask(FutureTarget<Bitmap> bm, ICallback iCallback) {
            this.mBitmap = bm;
            this.mICallback = iCallback;
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            try {
                return mBitmap.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            mICallback.done(bitmap);
        }

    }
}
