/*
 * Copyright (C) 2017 Buglife, Inc.
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
 *
 */

package com.buglife.sdk;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * On Android M and above, we use a ContentObserver to detect screenshots.
 */
@TargetApi(Build.VERSION_CODES.M)
final class ScreenshotContentObserver implements ScreenshotObserver {
    private static final String TAG = "ScreenshotObserver";
    private static final String EXTERNAL_CONTENT_URI_PREFIX = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString();
    private static final String[] PROJECTION = new String[] {
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
    };
    private static final String SORT_ORDER = MediaStore.Images.Media.DATE_ADDED + " DESC";
    private static final long DEFAULT_DETECT_WINDOW_SECONDS = 10;
    private static boolean sRequestedAndGrantedPermission = false;

    private final Context mAppContext;
    private final OnScreenshotTakenListener mListener;
    private ContentResolver mContentResolver = null;
    private ContentObserver mContentObserver = null;

    public ScreenshotContentObserver(Context appContext, OnScreenshotTakenListener listener) {
        mAppContext = appContext;
        mListener = listener;
    }

    private static boolean matchPath(String path) {
        return path.toLowerCase().contains("screenshot") || path.contains("截屏") || path.contains("截图");
    }

    private static boolean matchTime(long currentTime, long dateAdded) {
        return Math.abs(currentTime - dateAdded) <= DEFAULT_DETECT_WINDOW_SECONDS;
    }

    @Override
    public void start(final Activity currentActivity, final ScreenshotObserverPermissionListener permissionListener) {
        if (sRequestedAndGrantedPermission) {
            start();
            return;
        }

        FragmentManager fragmentManager = currentActivity.getFragmentManager();
        PermissionHelper permissionHelper = (PermissionHelper) fragmentManager.findFragmentByTag(PermissionHelper.TAG);

        if (permissionHelper == null) {
            permissionHelper = PermissionHelper.newInstance();
            permissionHelper.setPermissionCallback(new PermissionHelper.PermissionCallback() {
                @Override
                public void onPermissionGranted() {
                    sRequestedAndGrantedPermission = true;
                    start();
                }

                @Override
                public void onPermissionDenied() {
                    Log.w("Buglife needs read storage permission to capture screenshots!");
                    permissionListener.onPermissionDenied();
                }
            });
            fragmentManager.beginTransaction().add(permissionHelper, PermissionHelper.TAG).commit();
        }
    }

    private void start() {
        mContentResolver = mAppContext.getContentResolver();
        mContentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                super.onChange(selfChange, uri);
                if (uri == null || !uri.toString().startsWith(EXTERNAL_CONTENT_URI_PREFIX))
                    return;

                String screenshotPath = queryScreenshots(uri);

                if (screenshotPath != null) {
                    File file = new File(screenshotPath);
                    mListener.onScreenshotTaken(file);
                }
            }
        };

        mContentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mContentObserver);
    }

    private @Nullable String queryScreenshots(@NonNull Uri uri) {
        Cursor cursor = null;
        String screenshotPath = null;

        try {
            String path = null;
            long dateAdded = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String[] projection = new String[] {
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.IS_PENDING,
                        MediaStore.Images.Media.DATA
                };
                cursor = mContentResolver.query(uri, projection, null, null, SORT_ORDER);

                if (cursor != null && cursor.moveToFirst()) {
                    boolean pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) == 1;
                    path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
                    dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED));

                    if (pending) {
                        Log.d("Pending: " + path);
                        return null;
                    }
                }
            } else {
                cursor = mContentResolver.query(uri, PROJECTION, null, null, SORT_ORDER);

                if (cursor != null && cursor.moveToFirst()) {
                    path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
                    dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED));
                }
            }

            // Warning: If the user manually sets their system time to something else,
            // this may not work
            long currentTime = System.currentTimeMillis() / 1000;

            if (path != null && matchPath(path) && matchTime(currentTime, dateAdded)) {
                Log.d("Got screenshot: " + path);
                screenshotPath = path;
            }
        } catch (Exception e) {
            Log.e("Open cursor failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return screenshotPath;
    }

    @Override
    public void stop() {
        if (mContentResolver != null && mContentObserver != null) {
            mContentResolver.unregisterContentObserver(mContentObserver);
            mContentResolver = null;
            mContentObserver = null;
        }
    }
}
