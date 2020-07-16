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

package org.microg.gms.gcm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.support.annotation.RequiresApi;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;

import com.squareup.wire.Message;

import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.ForegroundServiceContext;
import org.microg.gms.common.PackageUtils;
import org.microg.gms.gcm.mcs.AppData;
import org.microg.gms.gcm.mcs.Close;
import org.microg.gms.gcm.mcs.DataMessageStanza;
import org.microg.gms.gcm.mcs.HeartbeatAck;
import org.microg.gms.gcm.mcs.HeartbeatPing;
import org.microg.gms.gcm.mcs.LoginRequest;
import org.microg.gms.gcm.mcs.LoginResponse;
import org.microg.gms.gcm.mcs.Setting;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLContext;

import okio.ByteString;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.os.Build.VERSION.SDK_INT;
import static org.microg.gms.common.ForegroundServiceContext.EXTRA_FOREGROUND;
import static org.microg.gms.gcm.GcmConstants.ACTION_C2DM_RECEIVE;
import static org.microg.gms.gcm.GcmConstants.EXTRA_APP;
import static org.microg.gms.gcm.GcmConstants.EXTRA_COLLAPSE_KEY;
import static org.microg.gms.gcm.GcmConstants.EXTRA_FROM;
import static org.microg.gms.gcm.GcmConstants.EXTRA_MESSAGE_ID;
import static org.microg.gms.gcm.GcmConstants.EXTRA_MESSENGER;
import static org.microg.gms.gcm.GcmConstants.EXTRA_REGISTRATION_ID;
import static org.microg.gms.gcm.GcmConstants.EXTRA_SEND_FROM;
import static org.microg.gms.gcm.GcmConstants.EXTRA_SEND_TO;
import static org.microg.gms.gcm.GcmConstants.EXTRA_TTL;
import static org.microg.gms.gcm.McsConstants.ACTION_CONNECT;
import static org.microg.gms.gcm.McsConstants.ACTION_HEARTBEAT;
import static org.microg.gms.gcm.McsConstants.ACTION_RECONNECT;
import static org.microg.gms.gcm.McsConstants.ACTION_SEND;
import static org.microg.gms.gcm.McsConstants.EXTRA_REASON;
import static org.microg.gms.gcm.McsConstants.MCS_CLOSE_TAG;
import static org.microg.gms.gcm.McsConstants.MCS_DATA_MESSAGE_STANZA_TAG;
import static org.microg.gms.gcm.McsConstants.MCS_HEARTBEAT_ACK_TAG;
import static org.microg.gms.gcm.McsConstants.MCS_HEARTBEAT_PING_TAG;
import static org.microg.gms.gcm.McsConstants.MCS_LOGIN_REQUEST_TAG;
import static org.microg.gms.gcm.McsConstants.MCS_LOGIN_RESPONSE_TAG;
import static org.microg.gms.gcm.McsConstants.MSG_CONNECT;
import static org.microg.gms.gcm.McsConstants.MSG_HEARTBEAT;
import static org.microg.gms.gcm.McsConstants.MSG_INPUT;
import static org.microg.gms.gcm.McsConstants.MSG_INPUT_ERROR;
import static org.microg.gms.gcm.McsConstants.MSG_OUTPUT;
import static org.microg.gms.gcm.McsConstants.MSG_OUTPUT_DONE;
import static org.microg.gms.gcm.McsConstants.MSG_OUTPUT_ERROR;
import static org.microg.gms.gcm.McsConstants.MSG_OUTPUT_READY;
import static org.microg.gms.gcm.McsConstants.MSG_TEARDOWN;

public class McsService extends Service implements Handler.Callback {
    private static final String TAG = "GmsGcmMcsSvc";

    public static final String SELF_CATEGORY = "com.google.android.gsf.gtalkservice";
    public static final String IDLE_NOTIFICATION = "IdleNotification";
    public static final String FROM_FIELD = "gcm@android.com";

    public static final String SERVICE_HOST = "mtalk.google.com";
    public static final int SERVICE_PORT = 5228;

    private static final int WAKELOCK_TIMEOUT = 5000;

    private static long lastHeartbeatAckElapsedRealtime = -1;
    private static long lastIncomingNetworkRealtime = 0;
    private static long startTimestamp = 0;
    public static String activeNetworkPref = null;

    private static Socket sslSocket;
    private static McsInputStream inputStream;
    private static McsOutputStream outputStream;

    private PendingIntent heartbeatIntent;

    private static HandlerThread handlerThread;
    private static Handler rootHandler;

    private GcmDatabase database;

    private AlarmManager alarmManager;
    private PowerManager powerManager;
    private static PowerManager.WakeLock wakeLock;

    private static long currentDelay = 0;

    private Intent connectIntent;

    private static int maxTtl = 24 * 60 * 60;

    private Object deviceIdleController;
    private Method getUserIdMethod;
    private Method addPowerSaveTempWhitelistAppMethod;

    private class HandlerThread extends Thread {

        public HandlerThread() {
            setName("McsHandler");
        }

        @Override
        public void run() {
            Looper.prepare();
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcs");
            wakeLock.setReferenceCounted(false);
            synchronized (McsService.class) {
                rootHandler = new Handler(Looper.myLooper(), McsService.this);
                if (connectIntent != null) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_CONNECT, connectIntent));
                    WakefulBroadcastReceiver.completeWakefulIntent(connectIntent);
                }
            }
            Looper.loop();
        }
    }

    private static void logd(String msg) {
        if (GcmPrefs.get(null).isGcmLogEnabled()) Log.d(TAG, msg);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TriggerReceiver.register(this);
        database = new GcmDatabase(this);
        heartbeatIntent = PendingIntent.getService(this, 0, new Intent(ACTION_HEARTBEAT, null, this, McsService.class), 0);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission("android.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST") == PackageManager.PERMISSION_GRANTED) {
            try {
                String deviceIdleControllerName = "deviceidle";
                try {
                    Field field = Context.class.getField("DEVICE_IDLE_CONTROLLER");
                    deviceIdleControllerName = (String) field.get(null);
                } catch (Exception ignored) {
                }
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, deviceIdleControllerName);
                if (binder != null) {
                    deviceIdleController = Class.forName("android.os.IDeviceIdleController$Stub")
                            .getMethod("asInterface", IBinder.class).invoke(null, binder);
                    getUserIdMethod = UserHandle.class.getMethod("getUserId", int.class);
                    addPowerSaveTempWhitelistAppMethod = deviceIdleController.getClass()
                            .getMethod("addPowerSaveTempWhitelistApp", String.class, long.class, int.class, String.class);
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }
        synchronized (McsService.class) {
            if (handlerThread == null) {
                handlerThread = new HandlerThread();
                handlerThread.start();
            }
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, McsService.class));
        closeAll();
    }

    @Override
    public void onDestroy() {
        alarmManager.cancel(heartbeatIntent);
        closeAll();
        database.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public synchronized static boolean isConnected() {
        if (inputStream == null || !inputStream.isAlive() || outputStream == null || !outputStream.isAlive()) {
            logd("Connection is not enabled or dead.");
            return false;
        }
        // consider connection to be dead if we did not receive an ack within twice the heartbeat interval
        int heartbeatMs = GcmPrefs.get(null).getHeartbeatMsFor(activeNetworkPref, false);
        if (heartbeatMs < 0) {
            closeAll();
        } else if (SystemClock.elapsedRealtime() - lastHeartbeatAckElapsedRealtime > 2 * heartbeatMs) {
            logd("No heartbeat for " + (SystemClock.elapsedRealtime() - lastHeartbeatAckElapsedRealtime) / 1000 + " seconds, connection assumed to be dead after " + 2 * heartbeatMs / 1000 + " seconds");
            GcmPrefs.get(null).learnTimeout(activeNetworkPref);
            return false;
        }
        return true;
    }

    public static long getStartTimestamp() {
        return startTimestamp;
    }

    public static void scheduleReconnect(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        long delay = getCurrentDelay();
        logd("Scheduling reconnect in " + delay / 1000 + " seconds...");
        PendingIntent pi = PendingIntent.getBroadcast(context, 1, new Intent(ACTION_RECONNECT, null, context, TriggerReceiver.class), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
        } else {
            alarmManager.set(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pi);
        }
    }

    public void scheduleHeartbeat(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);

        int heartbeatMs = GcmPrefs.get(this).getHeartbeatMsFor(activeNetworkPref, false);
        if (heartbeatMs < 0) {
            closeAll();
        }
        logd("Scheduling heartbeat in " + heartbeatMs / 1000 + " seconds...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // This is supposed to work even when running in idle and without battery optimization disabled
            alarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs, heartbeatIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // With KitKat, the alarms become inexact by default, but with the newly available setWindow we can get inexact alarms with guarantees.
            // Schedule the alarm to fire within the interval [heartbeatMs/3*4, heartbeatMs]
            alarmManager.setWindow(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs / 4 * 3, heartbeatMs / 4,
                    heartbeatIntent);
        } else {
            alarmManager.set(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + heartbeatMs, heartbeatIntent);
        }

    }

    public synchronized static long getCurrentDelay() {
        long delay = currentDelay == 0 ? 5000 : currentDelay;
        if (currentDelay < 60000) currentDelay += 10000;
        if (currentDelay >= 60000 && currentDelay < 600000) currentDelay += 60000;
        return delay;
    }

    public synchronized static void resetCurrentDelay() {
        currentDelay = 0;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ForegroundServiceContext.completeForegroundService(this, intent, TAG);
        synchronized (McsService.class) {
            if (rootHandler != null) {
                if (intent == null) return START_REDELIVER_INTENT;
                wakeLock.acquire(WAKELOCK_TIMEOUT);
                Object reason = intent.hasExtra(EXTRA_REASON) ? intent.getExtras().get(EXTRA_REASON) : intent;
                if (ACTION_CONNECT.equals(intent.getAction())) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_CONNECT, reason));
                } else if (ACTION_HEARTBEAT.equals(intent.getAction())) {
                    rootHandler.sendMessage(rootHandler.obtainMessage(MSG_HEARTBEAT, reason));
                } else if (ACTION_SEND.equals(intent.getAction())) {
                    handleSendMessage(intent);
                }
                WakefulBroadcastReceiver.completeWakefulIntent(intent);
            } else if (connectIntent == null) {
                connectIntent = intent;
            } else {
                WakefulBroadcastReceiver.completeWakefulIntent(intent);
            }
        }
        return START_REDELIVER_INTENT;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification buildForegroundNotification() {
        NotificationChannel channel = new NotificationChannel("foreground-service", "Foreground Service", NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new Notification.Builder(this, channel.getId())
                .setOngoing(true)
                .setContentTitle("Running in background")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
    }

    private void handleSendMessage(Intent intent) {
        String messageId = intent.getStringExtra(EXTRA_MESSAGE_ID);
        String collapseKey = intent.getStringExtra(EXTRA_COLLAPSE_KEY);

        Messenger messenger = intent.getParcelableExtra(EXTRA_MESSENGER);
        intent.removeExtra(EXTRA_MESSENGER);

        Parcelable app = intent.getParcelableExtra(EXTRA_APP);
        String packageName = null;
        if (app instanceof PendingIntent) {
            packageName = PackageUtils.packageFromPendingIntent((PendingIntent) app);
        }
        if (packageName == null) {
            Log.w(TAG, "Failed to send message, missing package name");
            return;
        }
        intent.removeExtra(EXTRA_APP);

        int ttl;
        try {
            ttl = Integer.parseInt(intent.getStringExtra(EXTRA_TTL));
            if (ttl < 0 || ttl > maxTtl) {
                ttl = maxTtl;
            }
        } catch (NumberFormatException e) {
            // TODO: error TtlUnsupported
            Log.w(TAG, e);
            return;
        }

        String to = intent.getStringExtra(EXTRA_SEND_TO);
        if (to == null) {
            // TODO: error missing_to
            Log.w(TAG, "missing to");
            return;
        }

        String from = intent.getStringExtra(EXTRA_SEND_FROM);
        if (from != null) {
            intent.removeExtra(EXTRA_SEND_FROM);
        } else {
            from = intent.getStringExtra(EXTRA_FROM);
        }
        if (from == null) {
            GcmDatabase.Registration reg = database.getRegistration(packageName, PackageUtils.firstSignatureDigest(this, packageName));
            if (reg != null) from = reg.registerId;
        }
        if (from == null) {
            Log.e(TAG, "Can't send message, missing from!");
            return;
        }

        String registrationId = intent.getStringExtra(EXTRA_REGISTRATION_ID);
        intent.removeExtra(EXTRA_REGISTRATION_ID);

        List<AppData> appData = new ArrayList<>();
        Bundle extras = intent.getExtras();
        for (String key : extras.keySet()) {
            if (!key.startsWith("google.")) {
                Object val = extras.get(key);
                if (val instanceof String) {
                    appData.add(new AppData(key, (String) val));
                }
            }
        }

        byte[] rawDataArray = intent.getByteArrayExtra("rawData");
        ByteString rawData = rawDataArray != null ? ByteString.of(rawDataArray) : null;

        try {
            DataMessageStanza msg = new DataMessageStanza.Builder()
                    .sent(System.currentTimeMillis() / 1000L)
                    .id(messageId)
                    .token(collapseKey)
                    .from(from)
                    .reg_id(registrationId)
                    .to(to)
                    .category(packageName)
                    .raw_data(rawData)
                    .app_data(appData).build();

            send(MCS_DATA_MESSAGE_STANZA_TAG, msg);
            database.noteAppMessage(packageName, msg.getSerializedSize());
        } catch (Exception e) {
            Log.w(TAG, e);
        }
    }

    private synchronized void connect() {
        try {
            closeAll();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            activeNetworkPref = GcmPrefs.get(this).getNetworkPrefForInfo(cm.getActiveNetworkInfo());
            if (!GcmPrefs.get(this).isEnabledFor(cm.getActiveNetworkInfo())) {
                scheduleReconnect(this);
                return;
            }

            logd("Starting MCS connection...");
            Socket socket = new Socket(SERVICE_HOST, SERVICE_PORT);
            logd("Connected to " + SERVICE_HOST + ":" + SERVICE_PORT);
            sslSocket = SSLContext.getDefault().getSocketFactory().createSocket(socket, SERVICE_HOST, SERVICE_PORT, true);
            logd("Activated SSL with " + SERVICE_HOST + ":" + SERVICE_PORT);
            inputStream = new McsInputStream(sslSocket.getInputStream(), rootHandler);
            outputStream = new McsOutputStream(sslSocket.getOutputStream(), rootHandler);
            inputStream.start();
            outputStream.start();

            startTimestamp = System.currentTimeMillis();
            lastHeartbeatAckElapsedRealtime = SystemClock.elapsedRealtime();
            lastIncomingNetworkRealtime = SystemClock.elapsedRealtime();
            scheduleHeartbeat(this);
        } catch (Exception e) {
            Log.w(TAG, "Exception while connecting!", e);
            rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, e));
        }
    }

    private void handleClose(Close close) {
        throw new RuntimeException("Server requested close!");
    }

    private void handleLoginResponse(LoginResponse loginResponse) {
        if (loginResponse.error == null) {
            GcmPrefs.get(this).clearLastPersistedId();
            logd("Logged in");
            wakeLock.release();
        } else {
            throw new RuntimeException("Could not login: " + loginResponse.error);
        }
    }

    private void handleCloudMessage(DataMessageStanza message) {
        if (message.persistent_id != null) {
            GcmPrefs.get(this).extendLastPersistedId(message.persistent_id);
        }
        if (SELF_CATEGORY.equals(message.category)) {
            handleSelfMessage(message);
        } else {
            handleAppMessage(message);
        }
    }

    private void handleHeartbeatPing(HeartbeatPing ping) {
        HeartbeatAck.Builder ack = new HeartbeatAck.Builder().status(ping.status);
        if (inputStream.newStreamIdAvailable()) {
            ack.last_stream_id_received(inputStream.getStreamId());
        }
        send(MCS_HEARTBEAT_ACK_TAG, ack.build());
    }

    private void handleHeartbeatAck(HeartbeatAck ack) {
        GcmPrefs.get(this).learnReached(activeNetworkPref, SystemClock.elapsedRealtime() - lastIncomingNetworkRealtime);
        lastHeartbeatAckElapsedRealtime = SystemClock.elapsedRealtime();
        wakeLock.release();
    }

    private LoginRequest buildLoginRequest() {
        LastCheckinInfo info = LastCheckinInfo.read(this);
        return new LoginRequest.Builder()
                .adaptive_heartbeat(false)
                .auth_service(LoginRequest.AuthService.ANDROID_ID)
                .auth_token(Long.toString(info.securityToken))
                .id("android-" + SDK_INT)
                .domain("mcs.android.com")
                .device_id("android-" + Long.toHexString(info.androidId))
                .network_type(1)
                .resource(Long.toString(info.androidId))
                .user(Long.toString(info.androidId))
                .use_rmq2(true)
                .setting(Collections.singletonList(new Setting("new_vc", "1")))
                .received_persistent_id(GcmPrefs.get(this).getLastPersistedIds())
                .build();
    }

    private void handleAppMessage(DataMessageStanza msg) {
        String packageName = msg.category;
        database.noteAppMessage(packageName, msg.getSerializedSize());
        GcmDatabase.App app = database.getApp(packageName);

        Intent intent = new Intent();
        intent.setAction(ACTION_C2DM_RECEIVE);
        intent.setPackage(packageName);
        intent.putExtra(EXTRA_FROM, msg.from);
        intent.putExtra(EXTRA_MESSAGE_ID, msg.id);
        if (app.wakeForDelivery) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        } else {
            intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
        }
        if (msg.token != null) intent.putExtra(EXTRA_COLLAPSE_KEY, msg.token);
        for (AppData appData : msg.app_data) {
            intent.putExtra(appData.key, appData.value);
        }

        String receiverPermission;
        try {
            String name = packageName + ".permission.C2D_MESSAGE";
            getPackageManager().getPermissionInfo(name, 0);
            receiverPermission = name;
        } catch (PackageManager.NameNotFoundException e) {
            receiverPermission = null;
        }

        List<ResolveInfo> infos = getPackageManager().queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER);
        if (infos == null || infos.isEmpty()) {
            logd("No target for message, wut?");
        } else {
            for (ResolveInfo resolveInfo : infos) {
                logd("Target: " + resolveInfo);
                Intent targetIntent = new Intent(intent);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && app.wakeForDelivery) {
                    try {
                        if (getUserIdMethod != null && addPowerSaveTempWhitelistAppMethod != null && deviceIdleController != null) {
                            int userId = (int) getUserIdMethod.invoke(null, getPackageManager().getApplicationInfo(packageName, 0).uid);
                            logd("Adding app " + packageName + " for userId " + userId + " to the temp whitelist");
                            addPowerSaveTempWhitelistAppMethod.invoke(deviceIdleController, packageName, 10000, userId, "GCM Push");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, e);
                    }
                }
                targetIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
                sendOrderedBroadcast(targetIntent, receiverPermission);
            }
        }
    }

    private void handleSelfMessage(DataMessageStanza msg) {
        for (AppData appData : msg.app_data) {
            if (IDLE_NOTIFICATION.equals(appData.key)) {
                DataMessageStanza.Builder msgResponse = new DataMessageStanza.Builder()
                        .from(FROM_FIELD)
                        .sent(System.currentTimeMillis() / 1000)
                        .ttl(0)
                        .category(SELF_CATEGORY)
                        .app_data(Collections.singletonList(new AppData(IDLE_NOTIFICATION, "false")));
                if (inputStream.newStreamIdAvailable()) {
                    msgResponse.last_stream_id_received(inputStream.getStreamId());
                }
                send(MCS_DATA_MESSAGE_STANZA_TAG, msgResponse.build());
            }
        }
    }

    private void send(int type, Message message) {
        rootHandler.sendMessage(rootHandler.obtainMessage(MSG_OUTPUT, type, 0, message));
    }

    private void sendOutputStream(int what, int arg, Object obj) {
        McsOutputStream os = outputStream;
        if (os != null && os.isAlive()) {
            Handler outputHandler = os.getHandler();
            if (outputHandler != null)
                outputHandler.sendMessage(outputHandler.obtainMessage(what, arg, 0, obj));
        }
    }

    @Override
    public boolean handleMessage(android.os.Message msg) {
        switch (msg.what) {
            case MSG_INPUT:
                handleInput(msg.arg1, (Message) msg.obj);
                return true;
            case MSG_OUTPUT:
                sendOutputStream(MSG_OUTPUT, msg.arg1, msg.obj);
                return true;
            case MSG_INPUT_ERROR:
            case MSG_OUTPUT_ERROR:
                logd("I/O error: " + msg.obj);
                rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, msg.obj));
                return true;
            case MSG_TEARDOWN:
                logd("Teardown initiated, reason: " + msg.obj);
                handleTeardown(msg);
                return true;
            case MSG_CONNECT:
                logd("Connect initiated, reason: " + msg.obj);
                if (!isConnected()) {
                    connect();
                }
                return true;
            case MSG_HEARTBEAT:
                logd("Heartbeat initiated, reason: " + msg.obj);
                if (isConnected()) {
                    HeartbeatPing.Builder ping = new HeartbeatPing.Builder();
                    if (inputStream.newStreamIdAvailable()) {
                        ping.last_stream_id_received(inputStream.getStreamId());
                    }
                    send(MCS_HEARTBEAT_PING_TAG, ping.build());
                    scheduleHeartbeat(this);
                } else {
                    logd("Ignoring heartbeat, not connected!");
                    scheduleReconnect(this);
                }
                return true;
            case MSG_OUTPUT_READY:
                logd("Sending login request...");
                send(MCS_LOGIN_REQUEST_TAG, buildLoginRequest());
                return true;
            case MSG_OUTPUT_DONE:
                handleOutputDone(msg);
                return true;
        }
        Log.w(TAG, "Unknown message: " + msg);
        return false;
    }

    private void handleOutputDone(android.os.Message msg) {
        switch (msg.arg1) {
            case MCS_HEARTBEAT_PING_TAG:
                wakeLock.release();
                break;
            default:
        }
    }

    private void handleInput(int type, Message message) {
        try {
            switch (type) {
                case MCS_DATA_MESSAGE_STANZA_TAG:
                    handleCloudMessage((DataMessageStanza) message);
                    break;
                case MCS_HEARTBEAT_PING_TAG:
                    handleHeartbeatPing((HeartbeatPing) message);
                    break;
                case MCS_HEARTBEAT_ACK_TAG:
                    handleHeartbeatAck((HeartbeatAck) message);
                    break;
                case MCS_CLOSE_TAG:
                    handleClose((Close) message);
                    break;
                case MCS_LOGIN_RESPONSE_TAG:
                    handleLoginResponse((LoginResponse) message);
                    break;
                default:
                    Log.w(TAG, "Unknown message: " + message);
            }
            resetCurrentDelay();
            lastIncomingNetworkRealtime = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            rootHandler.sendMessage(rootHandler.obtainMessage(MSG_TEARDOWN, e));
        }
    }

    private static void tryClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeAll() {
        tryClose(inputStream);
        tryClose(outputStream);
        if (sslSocket != null) {
            try {
                sslSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handleTeardown(android.os.Message msg) {
        closeAll();

        scheduleReconnect(this);

        alarmManager.cancel(heartbeatIntent);
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }
}
