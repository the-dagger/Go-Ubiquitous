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
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunShine extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    SimpleDateFormat df;
    Double highTemp = 0d;
    Double lowTemp = 0d;
    String desc = "";
    int weatherId = 0;
    Bitmap weatherIcon;
    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunShine.Engine> mWeakReference;

        public EngineHandler(SunShine.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunShine.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mWeatherPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient googleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunShine.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunShine.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            df = new SimpleDateFormat("dd MMM");
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mWeatherPaint = new Paint();
            mWeatherPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
            googleApiClient = new GoogleApiClient.Builder(SunShine.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            googleApiClient.connect();
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
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
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
            SunShine.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunShine.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunShine.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float weatherSize = resources.getDimension(isRound
                    ? R.dimen.weather_text_size_round : R.dimen.weather_text_size);

            mTextPaint.setTextSize(textSize);
            mWeatherPaint.setTextSize(weatherSize);
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
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
            canvas.drawText("High : " + highTemp.intValue(), mXOffset, (float) (1.4 * mYOffset), mWeatherPaint);
            canvas.drawText("Low : " + lowTemp.intValue(), mXOffset, (float) (1.7 * mYOffset), mWeatherPaint);
            canvas.drawText(desc, mXOffset, (float) (2.0 * mYOffset), mWeatherPaint);
            if (weatherIcon != null && !isInAmbientMode())
                canvas.drawBitmap(weatherIcon, (float) 7.5 * mXOffset, (float) (1.3 * mYOffset), null);
            if (isInAmbientMode()) {
                canvas.drawText(df.format(mCalendar.getTime()), 7 * mXOffset, mYOffset, mWeatherPaint);
            }
            if(!isInAmbientMode()){
                canvas.drawText(df.format(mCalendar.getTime()), 7 * mXOffset, (float) (2.0 * mYOffset), mWeatherPaint);
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

        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        DataItem item = event.getDataItem();
                        processConfigurationFor(item);
                    }
                }

                dataEvents.release();
                invalidate();
            }
        };

        private void processConfigurationFor(DataItem item) {
            if ("/weather_data".equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey("HIGH_TEMP")) {
                    highTemp = dataMap.getDouble("HIGH_TEMP");
                    Log.e("HighTemp", highTemp.toString());
                }

                if (dataMap.containsKey("LOW_TEMP")) {
                    lowTemp = dataMap.getDouble("LOW_TEMP");
                    Log.e("LowTemp", lowTemp.toString());
                }

                if (dataMap.containsKey("DESC")) {
                    desc = dataMap.getString("DESC");
                }

                if (dataMap.containsKey("ICON")) {
                    weatherId = dataMap.getInt("ICON");
                    updateWeathericon();
                    Log.e("Icon", String.valueOf(weatherId));
                }
                invalidate();
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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.e("Connected", "Connected");
            Wearable.DataApi.addListener(googleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(onConnectedResultCallback);
            invalidate();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e("Connection Suspended", "Connection Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        private void updateWeathericon() {
            Resources resources = SunShine.this.getResources();
            Drawable weatherBitmap;

            if (weatherId >= 200 && weatherId <= 232) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_storm, null);
            } else if (weatherId >= 300 && weatherId <= 321) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_light_rain, null);
            } else if (weatherId >= 500 && weatherId <= 504) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_rain, null);
            } else if (weatherId == 511) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_snow, null);
            } else if (weatherId >= 520 && weatherId <= 531) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_rain, null);
            } else if (weatherId >= 600 && weatherId <= 622) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_snow, null);
            } else if (weatherId >= 701 && weatherId <= 761) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_fog, null);
            } else if (weatherId == 761 || weatherId == 781) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_storm, null);
            } else if (weatherId == 800) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_clear, null);
            } else if (weatherId == 801) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_light_clouds, null);
            } else if (weatherId >= 802 && weatherId <= 804) {
                weatherBitmap = resources.getDrawable(R.drawable.ic_cloudy, null);
            } else { //default
                weatherBitmap = resources.getDrawable(R.drawable.ic_clear, null);
            }

            weatherIcon = ((BitmapDrawable) weatherBitmap).getBitmap();
        }

        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                for (DataItem item : dataItems) {
                    processConfigurationFor(item);
                }

                dataItems.release();
                invalidate();
            }
        };
    }
}
