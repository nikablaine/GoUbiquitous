/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    protected Bitmap mIcon;
    protected String mTempHiText;
    protected String mTempLoText;
    protected float mScaleSize;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        public static final String LOG_TAG = "Engine";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint, mTextDatePaint, mTextTempHiPaint, mTextTempLoPaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mYOffset, mYDateOffset, mYDividerOffset, mYTempOffset;
        float mDividerLength, mSpan;

        Bitmap mIcon;

        public static final String HIGH_KEY = "high";
        public static final String LOW_KEY = "low";
        public static final String WEATHER_ID_KEY = "weatherId";
        public static final String PATH = "/weather";

        public static final String GRAD_STRING = "°";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimensionPixelSize(R.dimen.digital_y_offset);
            mYDateOffset = resources.getDimensionPixelSize(R.dimen.digital_y_date_offset);
            mYDividerOffset = resources.getDimensionPixelSize(R.dimen.digital_y_divider_offset);
            mYTempOffset = resources.getDimensionPixelSize(R.dimen.y_temp_offset);
            mDividerLength = resources.getDimensionPixelSize(R.dimen.digital_divider_length);
            mSpan = resources.getDimensionPixelSize(R.dimen.space_after_pic);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.blue));

            mTextPaint = createTextPaint(resources.getColor(R.color.white));
            mTextDatePaint = createTextPaint(resources.getColor(R.color.light_grey));
            mTextTempHiPaint = createTextPaint(resources.getColor(R.color.white));
            mTextTempLoPaint = createTextPaint(resources.getColor(R.color.light_grey));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            float textSize = resources.getDimensionPixelSize(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textDateSize = resources.getDimensionPixelSize(isRound
                    ? R.dimen.secondary_digital_text_size_round : R.dimen.secondary_digital_text_size);
            float textTempSize = resources.getDimensionPixelSize(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

            mScaleSize = resources.getDimensionPixelSize(isRound
                    ? R.dimen.pict_size_round : R.dimen.pict_size);

            mTextPaint.setTextSize(textSize);
            mTextDatePaint.setTextSize(textDateSize);
            mTextTempHiPaint.setTextSize(textTempSize);
            mTextTempLoPaint.setTextSize(textTempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            int centerX = bounds.centerX();
            float xTimeOffset = centerX - mTextPaint.measureText(text) / 2;
            canvas.drawText(text, xTimeOffset, mYOffset, mTextPaint);
            String date = currentDate();
            float xDateOffset = centerX - mTextDatePaint.measureText(date) / 2;
            canvas.drawText(date, xDateOffset, mYDateOffset, mTextDatePaint);
            canvas.drawLine(centerX - mDividerLength, mYDividerOffset, centerX + mDividerLength, mYDividerOffset, mTextPaint);

            float textTempHiLength = mTempHiText == null ? 0f : mTextTempHiPaint.measureText(mTempHiText);
            float textTempLoLength = mTempLoText == null ? 0f : mTextTempLoPaint.measureText(mTempLoText);

            if (mAmbient) {
                float tempTextLength = textTempHiLength + textTempLoLength;
                float xTempHiOffset = centerX - tempTextLength / 2;
                canvas.drawText(mTempHiText == null ? "" : mTempHiText, xTempHiOffset, mYTempOffset, mTextTempHiPaint);
                canvas.drawText(mTempLoText == null ? "" : mTempLoText, xTempHiOffset + textTempHiLength, mYTempOffset, mTextTempLoPaint);
            } else {
                float tempLineLength = textTempHiLength + textTempLoLength + dp2px(mScaleSize) + dp2px(mSpan);
                float xBitmapOffset = centerX - tempLineLength / 2;
                float xTempHiOffset = xBitmapOffset + mScaleSize + mSpan;
                float topBitmap = mYTempOffset - mTextTempHiPaint.getTextSize() / 2 - dp2px(mScaleSize) / 2;
                if (mIcon != null) {
                    canvas.drawBitmap(mIcon, xBitmapOffset, topBitmap, null);
                }
                canvas.drawText(String.valueOf(mTempHiText), xTempHiOffset, mYTempOffset, mTextTempHiPaint);
                canvas.drawText(String.valueOf(mTempLoText), xTempHiOffset + textTempHiLength, mYTempOffset, mTextTempLoPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private String currentDate() {
            return getDay() + ", " + getMonth() + " " + mTime.monthDay + " " + mTime.year;
        }

        private String getMonth() {
            Calendar cal = GregorianCalendar.getInstance();
            cal.set(mTime.year, mTime.month, mTime.monthDay);
            return new SimpleDateFormat(getString(R.string.month_format))
                    .format(cal.getTime())
                    .toUpperCase();
        }

        private String getDay() {
            Calendar cal = GregorianCalendar.getInstance();
            cal.set(mTime.year, mTime.month, mTime.monthDay);
            return new SimpleDateFormat("EEE")
                    .format(cal.getTime())
                    .toUpperCase();
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged");

            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals(PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                        mTempHiText = String.valueOf(dataMap.getInt(HIGH_KEY)) + GRAD_STRING;
                        mTempLoText = String.valueOf(dataMap.getInt(LOW_KEY)) + GRAD_STRING;
                        int weatherId = dataMap.getInt(WEATHER_ID_KEY);
                        BitmapDrawable drawable = (BitmapDrawable) getResources().getDrawable(weatherId);
                        Bitmap bitmap = drawable.getBitmap();
                        mIcon = Bitmap.createScaledBitmap(bitmap, dp2px(mScaleSize), dp2px(mScaleSize), true);
                    }
                }
            }

            invalidate();
        }

        public void getInfoFromDevice() {
            Log.d(LOG_TAG,"getInfoFromDevice");
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(PATH);
            putDataMapRequest.getDataMap().putString("uuid", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(LOG_TAG, "Failed asking phone for weather data");
                            } else {
                                Log.d(LOG_TAG, "Successfully asked for weather data");
                            }
                        }
                    });
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected()");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            getInfoFromDevice();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

    }

    private int dp2px(float dipValue) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue, metrics);
    }
}
