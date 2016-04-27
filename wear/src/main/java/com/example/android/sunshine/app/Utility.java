package com.example.android.sunshine.app;

import android.content.Context;

public class Utility {

    public static int getResourceForWeatherCondition(Context context, int weatherId) {
        int id = 0;

        if (weatherId >= 200 && weatherId <= 232) {
            id = R.drawable.art_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            id = R.drawable.art_light_rain;
        } else if (weatherId >= 500 && weatherId <= 521) {
            id = R.drawable.art_rain;
        } else if (weatherId >= 600 && weatherId <= 621) {
            id = R.drawable.art_snow;
        } else if (weatherId >= 800 && weatherId <= 821) {
            id = R.drawable.art_clear;
        }
        
        return id;
    }
}
