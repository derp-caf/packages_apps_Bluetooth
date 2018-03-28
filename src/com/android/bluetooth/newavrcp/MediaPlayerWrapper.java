/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.avrcp;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.GuardedBy;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import java.util.List;

/*
 * A class to synchronize Media Controller Callbacks and only pass through
 * an update once all the relevant information is current.
 *
 * TODO (apanicke): Once MediaPlayer2 is supported better, replace this class
 * with that.
 */
class MediaPlayerWrapper {
    private static final String TAG = "NewAvrcpMediaPlayerWrapper";
    private static final boolean DEBUG = true;
    static boolean sTesting = false;

    private MediaController mMediaController;
    private String mPackageName;
    private Looper mLooper;

    private MediaData mCurrentData;

    @GuardedBy("mCallbackLock")
    private MediaControllerListener mControllerCallbacks = null;
    private final Object mCallbackLock = new Object();
    private Callback mRegisteredCallback = null;


    protected MediaPlayerWrapper() {
        mCurrentData = new MediaData(null, null, null);
    }

    public interface Callback {
        void mediaUpdatedCallback(MediaData data);
    }

    boolean isReady() {
        if (getPlaybackState() == null) {
            d("isReady(): PlaybackState is null");
            return false;
        }

        if (getMetadata() == null) {
            d("isReady(): Metadata is null");
            return false;
        }

        return true;
    }

    // TODO (apanicke): Implement a factory to make testing and creating interop wrappers easier
    static MediaPlayerWrapper wrap(MediaController controller, Looper looper) {
        if (controller == null || looper == null) {
            e("MediaPlayerWrapper.wrap(): Null parameter - Controller: " + controller
                    + " | Looper: " + looper);
            return null;
        }

        MediaPlayerWrapper newWrapper;
        if (controller.getPackageName().equals("com.google.android.music")) {
            Log.v(TAG, "Creating compatibility wrapper for Google Play Music");
            newWrapper = new GPMWrapper();
        } else {
            newWrapper = new MediaPlayerWrapper();
        }

        newWrapper.mMediaController = controller;
        newWrapper.mPackageName = controller.getPackageName();
        newWrapper.mLooper = looper;

        newWrapper.mCurrentData.queue = Util.toMetadataList(newWrapper.getQueue());
        newWrapper.mCurrentData.metadata = Util.toMetadata(newWrapper.getMetadata());
        newWrapper.mCurrentData.state = newWrapper.getPlaybackState();
        return newWrapper;
    }

    void cleanup() {
        unregisterCallback();

        mMediaController = null;
        mLooper = null;
    }

    String getPackageName() {
        return mPackageName;
    }

    protected List<MediaSession.QueueItem> getQueue() {
        return mMediaController.getQueue();
    }

    protected MediaMetadata getMetadata() {
        return mMediaController.getMetadata();
    }

    protected PlaybackState getPlaybackState() {
        return mMediaController.getPlaybackState();
    }

    long getActiveQueueID() {
        if (mMediaController.getPlaybackState() == null) return -1;
        return mMediaController.getPlaybackState().getActiveQueueItemId();
    }


    MediaData getCurrentMediaData() {
        return mCurrentData;
    }

    void playItemFromQueue(long qid) {
        // Return immediately if no queue exists.
        if (getQueue() == null) {
            Log.w(TAG, "playItemFromQueue: Trying to play item for player that has no queue: "
                    + mPackageName);
            return;
        }

        MediaController.TransportControls controller = mMediaController.getTransportControls();
        controller.skipToQueueItem(qid);
    }

    // TODO (apanicke): Implement shuffle and repeat support. Right now these use custom actions
    // and it may only be possible to do this with Google Play Music
    boolean isShuffleSupported() {
        return false;
    }

    boolean isRepeatSupported() {
        return false;
    }

    void toggleShuffle(boolean on) {
        return;
    }

    void toggleRepeat(boolean on) {
        return;
    }

    /**
     * Return whether the queue, metadata, and queueID are all in sync.
     */
    boolean isMetadataSynced() {
        if (getQueue() != null) {
            // Check if currentPlayingQueueId is in the current Queue
            MediaSession.QueueItem currItem = null;

            for (MediaSession.QueueItem item : getQueue()) {
                if (item.getQueueId()
                        == getActiveQueueID()) { // The item exists in the current queue
                    currItem = item;
                    break;
                }
            }

            // Check if current playing song in Queue matches current Metadata
            if (currItem == null
                    || !currItem.getDescription().equals(getMetadata().getDescription())) {
                if (DEBUG) {
                    Log.d(TAG, "Metadata currently out of sync for " + mPackageName);
                    Log.d(TAG, "  └ Current queueItem: " + currItem);
                    Log.d(TAG, "  └ Current metadata : " + getMetadata().getDescription());
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Register a callback which gets called when media updates happen. The callbacks are
     * called on the same Looper that was passed in to create this object.
     */
    void registerCallback(Callback callback) {
        if (callback == null) {
            e("Cannot register null callbacks for " + mPackageName);
            return;
        }

        synchronized (mCallbackLock) {
            mRegisteredCallback = callback;
        }
        mControllerCallbacks = new MediaControllerListener(mLooper);
    }

    /**
     * Unregisters from updates. Note, this doesn't require the looper to be shut down.
     */
    void unregisterCallback() {
        // Prevent a race condition where a callback could be called while shutting down
        synchronized (mCallbackLock) {
            mRegisteredCallback = null;
        }

        if (mControllerCallbacks == null) return;
        mControllerCallbacks.cleanup();
        mControllerCallbacks = null;
    }

    void updateMediaController(MediaController newController) {
        if (newController == mMediaController) return;

        synchronized (mCallbackLock) {
            if (mRegisteredCallback == null || mControllerCallbacks == null) {
                return;
            }
        }

        mControllerCallbacks.cleanup();
        mMediaController = newController;
        mControllerCallbacks = new MediaControllerListener(mLooper);
        d("Controller for " + mPackageName + " was updated.");
    }

    class TimeoutHandler extends Handler {
        private static final int MSG_TIMEOUT = 0;
        private static final long CALLBACK_TIMEOUT_MS = 1000;

        TimeoutHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_TIMEOUT) {
                Log.wtf(TAG, "Unknown message on timeout handler: " + msg.what);
                return;
            }

            Log.e(TAG, "Timeout while waiting for metadata to sync for " + mPackageName);
            Log.e(TAG, "  └ Current Metadata: " + getMetadata().getDescription());
            Log.e(TAG, "  └ Current Playstate: " + getPlaybackState());
            for (int i = 0; getQueue() != null && i < getQueue().size(); i++) {
                Log.e(TAG, "  └ QueueItem(" + i + "): " + getQueue().get(i));
            }

            // TODO(apanicke): Add metric collection here.

            if (sTesting) Log.wtfStack(TAG, "Crashing the stack");
        }
    }

    class MediaControllerListener extends MediaController.Callback {
        private final Object mTimeoutHandlerLock = new Object();
        private Handler mTimeoutHandler;

        MediaControllerListener(Looper newLooper) {
            synchronized (mTimeoutHandlerLock) {
                mTimeoutHandler = new TimeoutHandler(newLooper);

                // Register the callbacks to execute on the same thread as the timeout thread. This
                // prevents a race condition where a timeout happens at the same time as an update.
                mMediaController.registerCallback(this, mTimeoutHandler);
            }
        }

        void cleanup() {
            synchronized (mTimeoutHandlerLock) {
                mMediaController.unregisterCallback(this);
                mTimeoutHandler.removeMessages(TimeoutHandler.MSG_TIMEOUT);
                mTimeoutHandler = null;
            }
        }

        void trySendMediaUpdate() {
            synchronized (mTimeoutHandlerLock) {
                if (mTimeoutHandler == null) return;
                mTimeoutHandler.removeMessages(TimeoutHandler.MSG_TIMEOUT);

                if (!isMetadataSynced()) {
                    d("trySendMediaUpdate(): Starting media update timeout");
                    mTimeoutHandler.sendEmptyMessageDelayed(TimeoutHandler.MSG_TIMEOUT,
                            TimeoutHandler.CALLBACK_TIMEOUT_MS);
                    return;
                }
            }

            MediaData newData = new MediaData(
                    Util.toMetadata(getMetadata()),
                    getPlaybackState(),
                    Util.toMetadataList(getQueue()));

            if (newData.equals(mCurrentData)) {
                // This may happen if the controller is fully synced by the time the
                // first update is completed
                Log.v(TAG, "Trying to update with last sent metadata");
                return;
            }

            synchronized (mCallbackLock) {
                if (mRegisteredCallback == null) {
                    Log.e(TAG, mPackageName
                            + "Trying to send an update with no registered callback");
                    return;
                }

                Log.v(TAG, "trySendMediaUpdate(): Metadata has been updated for " + mPackageName);
                mRegisteredCallback.mediaUpdatedCallback(newData);
            }

            mCurrentData = newData;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (!isReady()) {
                Log.v(TAG, mPackageName + " tried to update with incomplete metadata");
                return;
            }

            Log.v(TAG, "onMetadataChanged(): " + mPackageName + " : " + metadata.getDescription());

            if (!metadata.equals(getMetadata())) {
                e("The callback metadata doesn't match controller metadata");
            }

            // TODO: Certain players update different metadata fields as they load, such as Album
            // Art. For track changed updates we only care about the song information like title
            // and album and duration. In the future we can use this to know when Album art is
            // loaded.

            // TODO: Spotify needs a metadata update debouncer as it sometimes updates the metadata
            // twice in a row with the only difference being that the song duration is rounded to
            // the nearest second.
            if (metadata.equals(mCurrentData.metadata)) {
                Log.w(TAG, "onMetadataChanged(): " + mPackageName
                        + " tried to update with no new data");
                return;
            }

            trySendMediaUpdate();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (!isReady()) {
                Log.v(TAG, mPackageName + " tried to update with no state");
                return;
            }

            Log.v(TAG, "onPlaybackStateChanged(): " + mPackageName + " : " + state.toString());

            if (!playstateEquals(state, getPlaybackState())) {
                e("The callback playback state doesn't match the current state");
            }

            if (playstateEquals(state, mCurrentData.state)) {
                Log.w(TAG, "onPlaybackStateChanged(): " + mPackageName
                        + " tried to update with no new data");
                return;
            }

            // If there is no playstate, ignore the update.
            if (state.getState() == PlaybackState.STATE_NONE) {
                Log.v(TAG, "Waiting to send update as controller has no playback state");
                return;
            }

            trySendMediaUpdate();
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            Log.v(TAG, "onQueueChanged(): " + mPackageName);

            if (!isReady()) {
                Log.v(TAG, mPackageName + " tried to updated with no queue");
                return;
            }

            if (!queue.equals(getQueue())) {
                e("The callback queue isn't the current queue");
            }
            if (queue.equals(mCurrentData.queue)) {
                Log.w(TAG, "onQueueChanged(): " + mPackageName
                        + " tried to update with no new data");
                return;
            }

            if (DEBUG) {
                for (int i = 0; i < queue.size(); i++) {
                    Log.d(TAG, "  └ QueueItem(" + i + "): " + queue.get(i));
                }
            }

            trySendMediaUpdate();
        }

        @Override
        public void onSessionDestroyed() {
            Log.w(TAG, "The session was destroyed " + mPackageName);
        }

        @VisibleForTesting
        Handler getTimeoutHandler() {
            return mTimeoutHandler;
        }
    }

    /**
     * Checks wheter the core information of two PlaybackStates match. This function allows a
     * certain amount of deviation between the position fields of the PlaybackStates. This is to
     * prevent matches from failing when updates happen in quick succession.
     *
     * The maximum allowed deviation is defined by PLAYSTATE_BOUNCE_IGNORE_PERIOD and is measured
     * in milliseconds.
     */
    private static final long PLAYSTATE_BOUNCE_IGNORE_PERIOD = 500;
    static boolean playstateEquals(PlaybackState a, PlaybackState b) {
        if (a == b) return true;

        if (a != null && b != null
                && a.getState() == b.getState()
                && a.getActiveQueueItemId() == b.getActiveQueueItemId()
                && Math.abs(a.getPosition() - b.getPosition()) < PLAYSTATE_BOUNCE_IGNORE_PERIOD) {
            return true;
        }

        return false;
    }

    // TODO: Use this function when returning the now playing list
    /**
     * Extracts different pieces of metadata from a MediaSession.QueueItem
     * and builds a MediaMetadata Object out of it.
     */
    MediaMetadata queueItemToMetadata(MediaSession.QueueItem item) {
        final String[] metadataStringKeys = {
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_GENRE };

        MediaMetadata.Builder newMetadata = new MediaMetadata.Builder();
        MediaDescription description = item.getDescription();
        Bundle extras = description.getExtras();

        for (String key : metadataStringKeys) {
            String value = extras.getString(key);

            if (key == MediaMetadata.METADATA_KEY_TITLE && value == null) {
                value = description.getTitle().toString();
            }

            if (value == null) {
                if (DEBUG) {
                    Log.d(TAG, "queueItemToMetadata: " + description + " is missing key: " + key);
                }
                continue;
            }
            newMetadata.putString(key, value);
        }

        long duration = extras.getLong(MediaMetadata.METADATA_KEY_DURATION);
        newMetadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);

        return newMetadata.build();
    }

    private static void e(String message) {
        if (sTesting) {
            Log.wtfStack(TAG, message);
        } else {
            Log.e(TAG, message);
        }
    }

    private void d(String message) {
        if (DEBUG) Log.d(TAG, mPackageName + ": " + message);
    }

    @VisibleForTesting
    Handler getTimeoutHandler() {
        if (mControllerCallbacks == null) return null;
        return mControllerCallbacks.getTimeoutHandler();
    }

    public void dump(StringBuilder sb) {
        sb.append(mMediaController.toString() + "\n");
    }
}