/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.common;

import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;

import com.google.android.gms.R;

import org.microg.gms.checkin.LastCheckinInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class Utils {
    public static String getAndroidIdHex(Context context) {
        return Long.toHexString(LastCheckinInfo.read(context).androidId);
    }

    public static Locale getLocale(Context context) {
        return Locale.getDefault(); // TODO
    }

    public static Build getBuild(Context context) {
        return new Build();
    }

    public static DeviceIdentifier getDeviceIdentifier(Context context) {
        return new DeviceIdentifier();
    }

    public static PhoneInfo getPhoneInfo(Context context) {
        return new PhoneInfo();
    }

    public static boolean hasSelfPermissionOrNotify(Context context, String permission) {
        if (ContextCompat.checkSelfPermission(context, permission) != PERMISSION_GRANTED) {
            Toast.makeText(context, context.getString(R.string.lacking_permission_toast, permission), Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    public static byte[] readStreamToEnd(final InputStream is) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            final byte[] buff = new byte[1024];
            int read;
            do {
                bos.write(buff, 0, (read = is.read(buff)) < 0 ? 0 : read);
            } while (read >= 0);
            is.close();
        }
        return bos.toByteArray();
    }
}
