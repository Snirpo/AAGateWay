package com.snirpoapps.aausbtowifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class ConnectionService extends Service {
    public static final String ACTION_STOP = ConnectionService.class.getName().toLowerCase() + ".action.stop";

    private static final String TAG = "AAGateWay";
    private static final String CHANNEL_ONE_ID = "com.snirpoapps.aausbtowifi";
    private static final String CHANNEL_ONE_NAME = "Channel One";

    private HandlerThread asyncHandlerThread;
    private Handler asyncHandler;

    private NotificationManager notificationManager;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private WifiP2pManager wifiP2PManager;
    private UsbManager usbManager;
    private SharedPreferences preferences;

    // async vars
    private Connection connection;
    private Network phoneNetwork;
    private UsbAccessory huUsbAccessory;

    private BroadcastReceiver usbBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equals(intent.getAction())) {
                final UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");
                asyncHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Objects.equals(huUsbAccessory, usbAccessory)) {
                            huUsbAccessory = null;
                            disconnect();
                        }
                    }
                });
            }
        }
    };

    private ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(final Network network) {
            asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    String phoneSSID = preferences.getString(Preferences.PHONE_SSID, "");
                    WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                    if (connectionInfo != null && phoneSSID.equals(connectionInfo.getSSID())) {
                        phoneNetwork = network;
                        tryConnect();
                    } else {
                        updateNotification("Not connected to phone SSID, please connect to correct SSID");
                    }
                }
            });
        }

        @Override
        public void onUnavailable() {
            asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateNotification("Not connected to wifi, please turn wifi on");
                    phoneNetwork = null;
                    disconnect();
                }
            });
        }
    };

    private BroadcastReceiver wifiDirectBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
//            String action = intent.getAction();
//            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
//                wifiP2PManager.requestPeers(wifip2pChannel, new WifiP2pManager.PeerListListener() {
//                    @Override
//                    public void onPeersAvailable(WifiP2pDeviceList peers) {
//                        for (WifiP2pDevice device: peers.getDeviceList()) {
//                            device.toString()
//                        }
//                    }
//                });
//            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
//                NetworkInfo networkInfo = intent
//                        .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
//                WifiP2pInfo p2pInfo = intent
//                        .getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
//
//                if (p2pInfo != null && p2pInfo.groupOwnerAddress != null) {
//                    String goAddress = p2pInfo.groupOwnerAddress.getHostAddress();
//                    boolean isGroupOwner = p2pInfo.isGroupOwner;
//                }
//                if (networkInfo.isConnected()) {
//                    // we are connected with the other device, request connection
//                    // info to find group owner IP
//                    manager.requestConnectionInfo(channel, activity);
//                } else {
//                    // It's a disconnect
//                    // activity.resetData();
//                }
//            }
        }
    };
    private WifiP2pManager.Channel wifip2pChannel;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiP2PManager = (WifiP2pManager) getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID,
                    CHANNEL_ONE_NAME, IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(notificationChannel);
        }

        startForeground(1, createNotification("Ready"));

        IntentFilter usbAccessoryFilter = new IntentFilter();
        usbAccessoryFilter.addAction("android.hardware.usb.action.USB_ACCESSORY_DETACHED");
        registerReceiver(usbBroadcastReceiver, usbAccessoryFilter);

        IntentFilter wifip2pFilter = new IntentFilter();
        wifip2pFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        wifip2pFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        wifip2pFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        wifip2pFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        registerReceiver(wifiDirectBroadcastReceiver, wifip2pFilter);

        NetworkRequest.Builder request = new NetworkRequest.Builder();
        request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        connectivityManager.registerNetworkCallback(request.build(), networkCallback);

        this.asyncHandlerThread = new HandlerThread("ConnectionServiceThread");
        this.asyncHandlerThread.start();
        this.asyncHandler = new Handler(asyncHandlerThread.getLooper());
        wifip2pChannel = wifiP2PManager.initialize(this, asyncHandlerThread.getLooper(), new WifiP2pManager.ChannelListener() {
            @Override
            public void onChannelDisconnected() {
                //TODO
            }
        });
//        wifiP2PManager.createGroup(wifip2pChannel, new WifiP2pManager.ActionListener() {
//            @Override
//            public void onSuccess() {
//                updateNotification("P2P group creation successful");
//            }
//
//            @Override
//            public void onFailure(int reason) {
//                updateNotification("P2P group creation failed. Retry.");
//            }
//        });
    }

    private void tryConnect() {
        disconnect();

        if (huUsbAccessory == null) {
            updateNotification("Waiting for headunit...");
            return;
        }

        if (phoneNetwork == null) {
            updateNotification("Waiting for phone...");
            return;
        }

        String phoneIpAddress = preferences.getString(Preferences.PHONE_IP_ADDRESS, null);
        if (phoneIpAddress == null || phoneIpAddress.isEmpty()) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            phoneIpAddress = Utils.intToIp(dhcpInfo.gateway);
        }
        updateNotification("Setting up Android Auto connection...");
        connection = new Connection(huUsbAccessory, phoneNetwork, phoneIpAddress);
        connection.connect();
    }

    private void disconnect() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent mainIntent = PendingIntent.getActivity(this, 0, intent, 0);

        Intent stopIntent = new Intent(this, ConnectionService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0,
                stopIntent, 0);

        Notification.Builder notification = new Notification.Builder(this)
                .setContentTitle("Android Auto USB to Wifi bridge")
                .setSmallIcon(R.drawable.aawifi)
                .setContentText(text)
                .setContentIntent(mainIntent)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent);
        if (Build.VERSION.SDK_INT >= 26)
            notification.setChannelId(CHANNEL_ONE_ID);
        return notification.build();
    }

    private void updateNotification(String text) {
        notificationManager.notify(1, createNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
        } else if (intent.hasExtra("accessory")) {
            final UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");
            asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    huUsbAccessory = usbAccessory;
                    tryConnect();
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        asyncHandler.post(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        });
        stopForeground(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ONE_ID);
        }
        connectivityManager.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(usbBroadcastReceiver);
        asyncHandlerThread.quitSafely();
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private class Connection implements Closeable, ErrorListener {
        private volatile boolean active = true;

        private final UsbAccessory usbAccessory;
        private final Network network;
        private final String ipAddress;

        private ParcelFileDescriptor huFileDescriptor;
        private Socket phoneSocket;

        private Pipe usbToWifiPipe;
        private Pipe wifiToUSBPipe;

        public Connection(UsbAccessory usbAccessory, Network network, String ipAddress) {
            this.usbAccessory = usbAccessory;
            this.network = network;
            this.ipAddress = ipAddress;
        }

        public void connect() {
            try {
                Log.d(TAG, "Connecting via USB to HU");
                huFileDescriptor = usbManager.openAccessory(usbAccessory);
                if (huFileDescriptor == null) {
                    throw new IllegalArgumentException("Accessory not found!");
                }
                FileDescriptor fileDescriptor = huFileDescriptor.getFileDescriptor();
                InputStream huInputStream = new FileInputStream(fileDescriptor);
                OutputStream huOutputStream = new FileOutputStream(fileDescriptor);

                Log.d(TAG, "HU connected, connecting to phone");

                phoneSocket = network.getSocketFactory().createSocket();
                phoneSocket.connect(InetSocketAddress.createUnresolved(ipAddress, 5277), 10000);
                InputStream phoneInputStream = phoneSocket.getInputStream();
                OutputStream phoneOutputStream = phoneSocket.getOutputStream();

                Log.d(TAG, "Connected to phone");

                usbToWifiPipe = new Pipe(huInputStream, phoneOutputStream, this);
                wifiToUSBPipe = new Pipe(phoneInputStream, huOutputStream, this);

                usbToWifiPipe.start();
                wifiToUSBPipe.start();

                updateNotification("Android auto connected");
            } catch (Exception e) {
                Log.d(TAG, "Could not connect", e);
                cleanup();
                updateNotification("Could not connect: " + e.getMessage());
            }
        }

        @Override
        public void close() {
            active = false;
            cleanup();
            updateNotification("Android auto disconnected");
        }

        @Override
        public void onError(final Exception e) {
            asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    cleanup();
                    if (active) {
                        updateNotification("Connection error: " + e.getMessage());
                    }
                }
            });
        }

        private void cleanup() {
            closeQuietly(usbToWifiPipe);
            closeQuietly(wifiToUSBPipe);

            closeQuietly(huFileDescriptor);
            closeQuietly(phoneSocket);
        }
    }

    private static class Pipe extends Thread implements Closeable {
        private ErrorListener errorListener;

        private InputStream inputStream;
        private OutputStream outputStream;

        private byte[] buffer = new byte[16384];
        private volatile boolean running = true;

        public Pipe(InputStream inputStream, OutputStream outputStream, ErrorListener errorListener) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.errorListener = errorListener;
        }

        @Override
        public void run() {
            int read;
            try {
                Log.d("AAGateway", "Pipe started reading");
                while (running && (read = inputStream.read(buffer)) > -1) {
                    outputStream.write(buffer, 0, read);
                    outputStream.flush();
                }
                Log.d("AAGateway", "Pipe stopped reading");
            } catch (IOException e) {
                Log.e("AAGateway", "Pipe error", e);
                if (running) {
                    errorListener.onError(e);
                }
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            running = false;
            closeQuietly(inputStream);
            closeQuietly(outputStream);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
