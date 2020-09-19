package com.codingwithmitch.audiostreamer.ui;

import android.support.v4.media.MediaMetadataCompat;

import com.codingwithmitch.audiostreamer.MyApplication;
import com.codingwithmitch.audiostreamer.models.Artist;
import com.codingwithmitch.audiostreamer.util.MyPreferenceManager;

public interface IMainActivity {

    void hideProgressBar();

    void showProgressBar();

    void onCategorySelected(String category);

    void onArtistSelected(String category, Artist artist);

    void setActionBarTitle(String title);

    void playPause();

    MyApplication getMyApplication();

    void onMediaSelected(String playlistId,MediaMetadataCompat mediaItem, int queuePosition);

    MyPreferenceManager getMyPrefManager();
}
