package com.example.android.sunshine.app.wear;

import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineListenerService extends WearableListenerService {
    public static final String LOG_TAG = SunshineListenerService.class.getSimpleName();
    private static final String WEATHER_PATH = "/weather";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                String path = dataEvent.getDataItem().getUri().getPath();
                Log.d(LOG_TAG, path);
                if (path.equals(WEATHER_PATH)) {
                    Log.d(LOG_TAG, "Syncing now");
                    SunshineSyncAdapter.syncImmediately(this);
                } else {
                    Log.d(LOG_TAG, "Ignoring " + path);
                }
            }
        }
    }

}
