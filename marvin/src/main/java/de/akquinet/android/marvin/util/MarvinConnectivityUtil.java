/*
 * Copyright 2010 akquinet
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.akquinet.android.marvin.util;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;


public class MarvinConnectivityUtil {

    private MarvinConnectivityUtil() {
    }

    public static boolean isAirplaneModeOn(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    public static void setAirplaneMode(
            Context context, boolean newStatus) {
        boolean airplaneModeOn = isAirplaneModeOn(context);

        if (airplaneModeOn && newStatus) {
            return;
        }
        if (!airplaneModeOn && !newStatus) {
            return;
        }
        if (airplaneModeOn && !newStatus) {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0);
            Intent intent = new Intent
                    (Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", 0);
            context.sendBroadcast(intent);
            return;
        }
        if (!airplaneModeOn && newStatus) {

            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 1);
            Intent intent = new Intent
                    (Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", 1);
            context.sendBroadcast(intent);
            return;
        }
    }
}
