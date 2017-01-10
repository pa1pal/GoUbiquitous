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

package pa1pal.myapplication;

import android.content.*;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.*;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
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

    private static final String PATH_WEATHER = "/weather";

    private static final String KEY_HIGH = "high_temp";

    private static final String KEY_LOW = "low_temp";

    private static final String KEY_ID = "weather_id";

    private String mHighTemp;

    private String mLowTemp;

    private int mWeatherId;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements com.google.android.gms.wearable.DataApi.DataListener, GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        Context context = getApplicationContext();

        SharedPreferences sharedPref = context.getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE);

        boolean mRegisteredTimeZoneReceiver = false;

        GoogleApiClient mGoogleApiClient;

        Paint mBackgroundPaint;

        Paint mTextTimePaint;

        Paint mTextDatePaint;

        Paint mTextTempLowPaint;

        Paint mTextTempHighPaint;

        Paint temperaturePaint;

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

        float mXTimeOffset;

        float mYTimeOffset;

        float mXDateOffset;

        float mYDateOffset;

        float mYOffsetLine;

        float mYOffsetWeather;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mHighTemp = sharedPref.getString(KEY_HIGH, null);
            mLowTemp = sharedPref.getString(KEY_LOW, null);
            mWeatherId = sharedPref.getInt(KEY_ID, 0);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();


            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextTimePaint = new Paint();
            mTextTimePaint = createTextTimePaint(resources.getColor(R.color.digital_text));

            mTextDatePaint = new Paint();
            mTextDatePaint = createTextDatePaint(resources.getColor(R.color.light_color));

            mTextTempLowPaint = new Paint();
            mTextTempLowPaint = createTextTempPaint(resources.getColor(R.color.light_color));

            mTextTempHighPaint = new Paint();
            mTextTempHighPaint = createTextTempPaint(resources.getColor(R.color.digital_text));

            mYTimeOffset = resources.getDimension(R.dimen.digital_y_offset_time);
            mYDateOffset = resources.getDimension(R.dimen.digital_y_offset_date);

            mYOffsetLine = resources.getDimension(R.dimen.y_offset_divider);
            mYOffsetWeather = resources.getDimension(R.dimen.y_offset_weather);

            mXTimeOffset = mTextTimePaint.measureText("12:00") / 2;
            mXDateOffset = mTextDatePaint.measureText("WED, JUN 13 2016") / 2;


            mTextDatePaint.setAntiAlias(true);
            mTextDatePaint.setTypeface(NORMAL_TYPEFACE);

            temperaturePaint = new Paint();
            temperaturePaint.setTypeface(NORMAL_TYPEFACE);
            temperaturePaint.setColor(resources.getColor(R.color.digital_text));
            temperaturePaint.setAntiAlias(true);
            mGoogleApiClient.connect();

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextTimePaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(getResources().getDimension(R.dimen.digital_time_text_size));
            return paint;
        }

        private Paint createTextDatePaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(getResources().getDimension(R.dimen.digital_date_text_size));
            return paint;
        }

        private Paint createTextTempPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(getResources().getDimension(R.dimen.digital_temp_text_size));
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                mGoogleApiClient.disconnect();
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            if (isRound) {
                mTextDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size_round));
                temperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size_round));
            } else {
                mTextDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
                temperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));
            }
            mTextTimePaint.setTextSize(textSize);
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
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
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
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                mTextDatePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                mTextDatePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.primary_light));
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String timeText = String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));
            String dateText = new SimpleDateFormat("EEE, MMM d, ''yyyy", Locale.ENGLISH).format(now);

            canvas.drawText(timeText, bounds.centerX() - mXTimeOffset, mYTimeOffset, mTextTimePaint);
            canvas.drawText(dateText, bounds.centerX() - mXDateOffset, mYDateOffset, mTextDatePaint);
            //canvas.drawText((highTemperatureText + " | " + lowTemperatureText), mXOffset * 3, offsetTemp + roundOffset, temperaturePaint);

            if (mHighTemp != null && mLowTemp != null) {
                canvas.drawLine(bounds.centerX() - 25, mYOffsetLine, bounds.centerX() + 25, mYOffsetLine, mTextDatePaint);
                float highTextSize = mTextTempHighPaint.measureText(mHighTemp);

                if (mAmbient) {
                    mTextTempLowPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
                    float lowTextSize = mTextTempLowPaint.measureText(mLowTemp);
                    float xOffset = bounds.centerX() - ((highTextSize + lowTextSize) / 2);
                    canvas.drawText(mHighTemp, xOffset, mYOffsetWeather, mTextTempHighPaint);
                    canvas.drawText(mLowTemp, xOffset + highTextSize + 20, mYOffsetWeather, mTextTempLowPaint);
                } else {
                    mTextTempLowPaint.setColor(ContextCompat.getColor(getApplicationContext(), R
                            .color.primary_light));
                    float xOffset = bounds.centerX() - (highTextSize / 2);
                    canvas.drawText(mHighTemp, xOffset, mYOffsetWeather, mTextTempHighPaint);
                    canvas.drawText(mLowTemp, bounds.centerX() + (highTextSize / 2),
                            mYOffsetWeather, mTextTempLowPaint);

                    Drawable b = getIconResourceForWeatherCondition(getApplicationContext(), mWeatherId);
                    Bitmap icon = ((BitmapDrawable) b).getBitmap();
                    float scaledWidth = (mTextTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                    Bitmap weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextTempHighPaint.getTextSize(), true);
                    float iconXOffset = bounds.centerX() - ((highTextSize / 2) + weatherIcon.getWidth() + 15);
                    canvas.drawBitmap(weatherIcon, iconXOffset, mYOffsetWeather - weatherIcon
                            .getHeight() + 10, null);
                }
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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d("GOOGLE_API_CLIENT", "Connected");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("GOOGLE_API_CLIENT", "Connection Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("GOOGLE_API_CLIENT", "Connection Failed: " + connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        SharedPreferences.Editor editor = sharedPref.edit();
                        editor.putString(KEY_HIGH, dataMap.getString(KEY_HIGH));
                        editor.putString(KEY_LOW, dataMap.getString(KEY_LOW));
                        editor.putInt(KEY_ID, dataMap.getInt(KEY_ID));
                        editor.apply();

                        mHighTemp = dataMap.getString(KEY_HIGH);
                        mLowTemp = dataMap.getString(KEY_LOW);
                        mWeatherId = dataMap.getInt(KEY_ID);

                        Log.d("WATCH_DATA", "\nHigh: " + mHighTemp + "\nLow: " + mLowTemp + "\nID: " + mWeatherId);
                        invalidate();
                    }
                }
            }
        }

        Drawable getIconResourceForWeatherCondition(Context context, int weatherId) {
            // Based on weather code data found at:
            // http://openweathermap.org/weather-conditions
            if (weatherId >= 200 && weatherId <= 232) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_storm);
            } else if (weatherId >= 300 && weatherId <= 321) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_light_rain);
            } else if (weatherId >= 500 && weatherId <= 504) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_rain);
            } else if (weatherId == 511) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_snow);
            } else if (weatherId >= 520 && weatherId <= 531) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_rain);
            } else if (weatherId >= 600 && weatherId <= 622) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_snow);
            } else if (weatherId >= 701 && weatherId <= 761) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_fog);
            } else if (weatherId == 761 || weatherId == 781) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_storm);
            } else if (weatherId == 800) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_clear);
            } else if (weatherId == 801) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_light_clouds);
            } else if (weatherId >= 802 && weatherId <= 804) {
                return ContextCompat.getDrawable(context, R.mipmap.ic_cloudy);
            }
            return ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
        }
    }
}
