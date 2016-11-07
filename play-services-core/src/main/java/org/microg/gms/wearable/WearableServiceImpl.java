/*
 * Copyright 2013-2015 microG Project Team
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

package org.microg.gms.wearable;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.ConnectionConfiguration;
import com.google.android.gms.wearable.internal.AddListenerRequest;
import com.google.android.gms.wearable.internal.AncsNotificationParcelable;
import com.google.android.gms.wearable.internal.DeleteDataItemsResponse;
import com.google.android.gms.wearable.internal.GetCloudSyncSettingResponse;
import com.google.android.gms.wearable.internal.GetConfigResponse;
import com.google.android.gms.wearable.internal.GetConfigsResponse;
import com.google.android.gms.wearable.internal.GetConnectedNodesResponse;
import com.google.android.gms.wearable.internal.GetDataItemResponse;
import com.google.android.gms.wearable.internal.GetFdForAssetResponse;
import com.google.android.gms.wearable.internal.GetLocalNodeResponse;
import com.google.android.gms.wearable.internal.IChannelStreamCallbacks;
import com.google.android.gms.wearable.internal.IWearableCallbacks;
import com.google.android.gms.wearable.internal.IWearableService;
import com.google.android.gms.wearable.internal.NodeParcelable;
import com.google.android.gms.wearable.internal.PutDataRequest;
import com.google.android.gms.wearable.internal.PutDataResponse;
import com.google.android.gms.wearable.internal.RemoveListenerRequest;
import com.google.android.gms.wearable.internal.SendMessageResponse;

import java.io.FileNotFoundException;

public class WearableServiceImpl extends IWearableService.Stub {
    private static final String TAG = "GmsWearSvcImpl";

    private final Context context;
    private final String packageName;
    private final WearableImpl wearable;
    private final Handler handler;

    public WearableServiceImpl(Context context, WearableImpl wearable, String packageName) {
        this.context = context;
        this.wearable = wearable;
        this.packageName = packageName;
        this.handler = new Handler(context.getMainLooper());
    }

    /*
     * Config
     */

    @Override
    public void putConfig(IWearableCallbacks callbacks, final ConnectionConfiguration config) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                wearable.createConnection(config);
                callbacks.onStatus(Status.SUCCESS);
            }
        });
    }

    @Override
    public void deleteConfig(IWearableCallbacks callbacks, final String name) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                wearable.deleteConnection(name);
                callbacks.onStatus(Status.SUCCESS);
            }
        });
    }

    @Override
    public void getConfigs(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "getConfigs");
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                try {
                    callbacks.onGetConfigsResponse(new GetConfigsResponse(0, wearable.getConfigurations()));
                } catch (Exception e) {
                    callbacks.onGetConfigsResponse(new GetConfigsResponse(8, new ConnectionConfiguration[0]));
                }
            }
        });
    }


    @Override
    public void enableConfig(IWearableCallbacks callbacks, final String name) throws RemoteException {
        Log.d(TAG, "enableConfig: " + name);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                wearable.enableConnection(name);
                callbacks.onStatus(Status.SUCCESS);
            }
        });
    }

    @Override
    public void disableConfig(IWearableCallbacks callbacks, final String name) throws RemoteException {
        Log.d(TAG, "disableConfig: " + name);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                wearable.disableConnection(name);
                callbacks.onStatus(Status.SUCCESS);
            }
        });
    }

    /*
     * DataItems
     */

    @Override
    public void putData(IWearableCallbacks callbacks, final PutDataRequest request) throws RemoteException {
        Log.d(TAG, "putData: " + request.toString(true));
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                DataItemRecord record = wearable.putData(request, packageName);
                callbacks.onPutDataResponse(new PutDataResponse(0, record.toParcelable()));
            }
        });
    }

    @Override
    public void getDataItem(IWearableCallbacks callbacks, final Uri uri) throws RemoteException {
        Log.d(TAG, "getDataItem: " + uri);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                DataItemRecord record = wearable.getDataItemByUri(uri, packageName);
                if (record != null) {
                    callbacks.onGetDataItemResponse(new GetDataItemResponse(0, record.toParcelable()));
                } else {
                    callbacks.onGetDataItemResponse(new GetDataItemResponse(0, null));
                }
            }
        });
    }

    @Override
    public void getDataItems(final IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "getDataItems: " + callbacks);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                callbacks.onDataItemChanged(wearable.getDataItemsAsHolder(packageName));
            }
        });
    }

    @Override
    public void getDataItemsByUri(IWearableCallbacks callbacks, Uri uri) throws RemoteException {
        getDataItemsByUriWithFilter(callbacks, uri, 0);
    }

    @Override
    public void getDataItemsByUriWithFilter(IWearableCallbacks callbacks, final Uri uri, int typeFilter) throws RemoteException {
        Log.d(TAG, "getDataItemsByUri: " + uri);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                callbacks.onDataItemChanged(wearable.getDataItemsByUriAsHolder(uri, packageName));
            }
        });
    }

    @Override
    public void deleteDataItems(IWearableCallbacks callbacks, Uri uri) throws RemoteException {
        deleteDataItemsWithFilter(callbacks, uri, 0);
    }

    @Override
    public void deleteDataItemsWithFilter(IWearableCallbacks callbacks, final Uri uri, int typeFilter) throws RemoteException {
        Log.d(TAG, "deleteDataItems: " + uri);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                callbacks.onDeleteDataItemsResponse(new DeleteDataItemsResponse(0, wearable.deleteDataItems(uri, packageName)));
            }
        });
    }

    @Override
    public void sendMessage(IWearableCallbacks callbacks, final String targetNodeId, final String path, final byte[] data) throws RemoteException {
        Log.d(TAG, "sendMessage: " + targetNodeId + " / " + path + ": " + (data == null ? null : Base64.encodeToString(data, Base64.NO_WRAP)));
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                SendMessageResponse sendMessageResponse = new SendMessageResponse();
                try {
                    sendMessageResponse.requestId = wearable.sendMessage(packageName, targetNodeId, path, data);
                    if (sendMessageResponse.requestId == -1) {
                        sendMessageResponse.statusCode = 4000;
                    }
                } catch (Exception e) {
                    sendMessageResponse.statusCode = 8;
                }
                callbacks.onSendMessageResponse(sendMessageResponse);
            }
        });
    }

    @Override
    public void getFdForAsset(IWearableCallbacks callbacks, final Asset asset) throws RemoteException {
        Log.d(TAG, "getFdForAsset " + asset);
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                // TODO: Access control
                try {
                    callbacks.onGetFdForAssetResponse(new GetFdForAssetResponse(0, ParcelFileDescriptor.open(wearable.createAssetFile(asset.getDigest()), ParcelFileDescriptor.MODE_READ_ONLY)));
                } catch (FileNotFoundException e) {
                    callbacks.onGetFdForAssetResponse(new GetFdForAssetResponse(8, null));
                }
            }
        });
    }

    @Override
    public void optInCloudSync(IWearableCallbacks callbacks, boolean enable) throws RemoteException {
        callbacks.onStatus(Status.SUCCESS);
    }

    @Override
    @Deprecated
    public void getCloudSyncOptInDone(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getCloudSyncOptInDone");
    }

    @Override
    public void setCloudSyncSetting(IWearableCallbacks callbacks, boolean enable) throws RemoteException {
        Log.d(TAG, "unimplemented Method: setCloudSyncSetting");
    }

    @Override
    public void getCloudSyncSetting(IWearableCallbacks callbacks) throws RemoteException {
        callbacks.onGetCloudSyncSettingResponse(new GetCloudSyncSettingResponse(0, false));
    }

    @Override
    public void getCloudSyncOptInStatus(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getCloudSyncOptInStatus");
    }

    @Override
    public void sendRemoteCommand(IWearableCallbacks callbacks, byte b) throws RemoteException {
        Log.d(TAG, "unimplemented Method: sendRemoteCommand: " + b);
    }

    @Override
    public void getLocalNode(IWearableCallbacks callbacks) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                try {
                    callbacks.onGetLocalNodeResponse(new GetLocalNodeResponse(0, new NodeParcelable(wearable.getLocalNodeId(), wearable.getLocalNodeId())));
                } catch (Exception e) {
                    callbacks.onGetLocalNodeResponse(new GetLocalNodeResponse(8, null));
                }
            }
        });
    }

    @Override
    public void getConnectedNodes(IWearableCallbacks callbacks) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                callbacks.onGetConnectedNodesResponse(new GetConnectedNodesResponse(0, wearable.getConnectedNodesParcelableList()));
            }
        });
    }

    /*
     * Capability
     */

    @Override
    public void getConnectedCapability(IWearableCallbacks callbacks, String s1, int i) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getConnectedCapability " + s1 + ", " + i);
    }

    @Override
    public void getConnectedCapaibilties(IWearableCallbacks callbacks, int i) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getConnectedCapaibilties: " + i);
    }

    @Override
    public void addLocalCapability(IWearableCallbacks callbacks, String cap) throws RemoteException {
        Log.d(TAG, "unimplemented Method: addLocalCapability: " + cap);
    }

    @Override
    public void removeLocalCapability(IWearableCallbacks callbacks, String cap) throws RemoteException {
        Log.d(TAG, "unimplemented Method: removeLocalCapability: " + cap);
    }

    @Override
    public void addListener(IWearableCallbacks callbacks, AddListenerRequest request) throws RemoteException {
        if (request.listener != null) {
            wearable.addListener(packageName, request.listener);
        }
        callbacks.onStatus(Status.SUCCESS);
    }

    @Override
    public void removeListener(IWearableCallbacks callbacks, RemoveListenerRequest request) throws RemoteException {
        wearable.removeListener(request.listener);
        callbacks.onStatus(Status.SUCCESS);
    }

    @Override
    public void getStorageInformation(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getStorageInformation");
    }

    @Override
    public void clearStorage(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: clearStorage");
    }

    @Override
    public void endCall(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: endCall");
    }

    @Override
    public void acceptRingingCall(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: acceptRingingCall");
    }

    @Override
    public void silenceRinger(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: silenceRinger");
    }

    /*
     * Apple Notification Center Service
     */

    @Override
    public void injectAncsNotificationForTesting(IWearableCallbacks callbacks, AncsNotificationParcelable notification) throws RemoteException {
        Log.d(TAG, "unimplemented Method: injectAncsNotificationForTesting: " + notification);
    }

    @Override
    public void doAncsPositiveAction(IWearableCallbacks callbacks, int i) throws RemoteException {
        Log.d(TAG, "unimplemented Method: doAncsPositiveAction: " + i);
    }

    @Override
    public void doAncsNegativeAction(IWearableCallbacks callbacks, int i) throws RemoteException {
        Log.d(TAG, "unimplemented Method: doAncsNegativeAction: " + i);
    }

    @Override
    public void openChannel(IWearableCallbacks callbacks, String s1, String s2) throws RemoteException {
        Log.d(TAG, "unimplemented Method: openChannel; " + s1 + ", " + s2);
    }

    /*
     * Channels
     */

    @Override
    public void closeChannel(IWearableCallbacks callbacks, String s) throws RemoteException {
        Log.d(TAG, "unimplemented Method: closeChannel: " + s);
    }

    @Override
    public void closeChannelWithError(IWearableCallbacks callbacks, String s, int errorCode) throws RemoteException {
        Log.d(TAG, "unimplemented Method: closeChannelWithError:" + s + ", " + errorCode);

    }

    @Override
    public void getChannelInputStream(IWearableCallbacks callbacks, IChannelStreamCallbacks channelCallbacks, String s) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getChannelInputStream: " + s);
    }

    @Override
    public void getChannelOutputStream(IWearableCallbacks callbacks, IChannelStreamCallbacks channelCallbacks, String s) throws RemoteException {
        Log.d(TAG, "unimplemented Method: getChannelOutputStream: " + s);
    }

    @Override
    public void writeChannelInputToFd(IWearableCallbacks callbacks, String s, ParcelFileDescriptor fd) throws RemoteException {
        Log.d(TAG, "unimplemented Method: writeChannelInputToFd: " + s);
    }

    @Override
    public void readChannelOutputFromFd(IWearableCallbacks callbacks, String s, ParcelFileDescriptor fd, long l1, long l2) throws RemoteException {
        Log.d(TAG, "unimplemented Method: readChannelOutputFromFd: " + s + ", " + l1 + ", " + l2);
    }

    @Override
    public void syncWifiCredentials(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "unimplemented Method: syncWifiCredentials");
    }

    /*
     * Connection deprecated
     */

    @Override
    @Deprecated
    public void putConnection(IWearableCallbacks callbacks, ConnectionConfiguration config) throws RemoteException {
        Log.d(TAG, "unimplemented Method: putConnection");
    }

    @Override
    @Deprecated
    public void getConnection(IWearableCallbacks callbacks) throws RemoteException {
        Log.d(TAG, "getConfig");
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                ConnectionConfiguration[] configurations = wearable.getConfigurations();
                if (configurations == null || configurations.length == 0) {
                    callbacks.onGetConfigResponse(new GetConfigResponse(1, new ConnectionConfiguration(null, null, 0, 0, false)));
                } else {
                    callbacks.onGetConfigResponse(new GetConfigResponse(0, configurations[0]));
                }
            }
        });
    }

    @Override
    @Deprecated
    public void enableConnection(IWearableCallbacks callbacks) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                ConnectionConfiguration[] configurations = wearable.getConfigurations();
                if (configurations.length > 0) {
                    enableConfig(callbacks, configurations[0].name);
                }
            }
        });
    }

    @Override
    @Deprecated
    public void disableConnection(IWearableCallbacks callbacks) throws RemoteException {
        handler.post(new CallbackRunnable(callbacks) {
            @Override
            public void run(IWearableCallbacks callbacks) throws RemoteException {
                ConnectionConfiguration[] configurations = wearable.getConfigurations();
                if (configurations.length > 0) {
                    disableConfig(callbacks, configurations[0].name);
                }
            }
        });
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (super.onTransact(code, data, reply, flags)) return true;
        Log.d(TAG, "onTransact [unknown]: " + code + ", " + data + ", " + flags);
        return false;
    }

    public abstract class CallbackRunnable implements Runnable {
        private IWearableCallbacks callbacks;

        public CallbackRunnable(IWearableCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void run() {
            try {
                run(callbacks);
            } catch (RemoteException e) {
                try {
                    callbacks.onStatus(Status.CANCELED);
                } catch (RemoteException e1) {
                    Log.w(TAG, e);
                }
            }
        }

        public abstract void run(IWearableCallbacks callbacks) throws RemoteException;
    }
}
