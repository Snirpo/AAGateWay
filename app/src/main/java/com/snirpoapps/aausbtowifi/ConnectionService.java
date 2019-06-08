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
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
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
import java.net.Socket;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class ConnectionService extends Service {
    public static final String ACTION_STOP = ConnectionService.class.getName().toLowerCase() + ".action.stop";

    private static final String TAG = "AAGateWay";
    private static final String CHANNEL_ONE_ID = "com.snirpoapps.aausbtowifi";
    private static final String CHANNEL_ONE_NAME = "Channel One";
    private static final Long RECONNECT_DELAY = 5000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private SharedPreferences preferences;

    private Connection connection;
    private Network phoneNetwork;
    private UsbAccessory huUsbAccessory;

    private BroadcastReceiver usbBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equals(intent.getAction())) {
                huUsbAccessory = intent.getParcelableExtra("accessory");
                tryConnect();
            } else if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equals(intent.getAction())) {
                huUsbAccessory = null;
                disconnect();
            }
        }
    };

    private ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            String phoneSSID = preferences.getString(Preferences.PHONE_SSID, "");
            WifiInfo connectionInfo = wifiManager.getConnectionInfo();
            if (connectionInfo != null && phoneSSID.equals(connectionInfo.getSSID())) {
                phoneNetwork = network;
                tryConnect();
            } else {
                notificationManager.notify(1, createNotification("Not connected to phone SSID, please connect to correct SSID"));
                //connectToPhone(phoneSSID);
            }
        }

        @Override
        public void onUnavailable() {
            notificationManager.notify(1, createNotification("Not connected to wifi, please turn wifi on"));
            phoneNetwork = null;
            disconnect();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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

        startForeground(1, createNotification("Idle"));

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_ACCESSORY_ATTACHED");
        filter.addAction("android.hardware.usb.action.USB_ACCESSORY_DETACHED");
        registerReceiver(usbBroadcastReceiver, filter);

        NetworkRequest.Builder request = new NetworkRequest.Builder();
        request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        connectivityManager.registerNetworkCallback(request.build(), networkCallback);
    }

    private void tryConnect() {
        disconnect();

        if (huUsbAccessory == null) {
            notificationManager.notify(1, createNotification("Waiting for headunit..."));
            return;
        }

        if (phoneNetwork == null) {
            notificationManager.notify(1, createNotification("Waiting for phone..."));
            return;
        }

        String phoneIpAddress = preferences.getString(Preferences.PHONE_IP_ADDRESS, null);
        if (phoneIpAddress == null || phoneIpAddress.isEmpty()) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            phoneIpAddress = Utils.intToIp(dhcpInfo.gateway);
        }
        notificationManager.notify(1, createNotification("Setting up Android Auto connection..."));
        connection = new Connection(huUsbAccessory, phoneNetwork, phoneIpAddress);
        connection.connect();
    }

    private void disconnect() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private boolean connectToPhone(String phoneSSID) {
        for (WifiConfiguration config : wifiManager.getConfiguredNetworks()) {
            if (phoneSSID.equals(config.SSID)) {
                wifiManager.disconnect();
                wifiManager.enableNetwork(config.networkId, true);
                wifiManager.reconnect();
                return true;
            }
        }
        return false;
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        disconnect();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ONE_ID);
        }
        connectivityManager.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(usbBroadcastReceiver);
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private class Connection implements Closeable, ErrorListener {
        private boolean running = true;

        private final UsbAccessory usbAccessory;
        private final Network network;
        private final String ipAddress;

        private Socket phoneSocket;

        private Pipe usbToWifiPipe;
        private Pipe wifiToUSBPipe;

        private final HandlerThread handlerThread;
        private final Handler asyncHandler;

        private final Runnable reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                connect();
            }
        };

        public Connection(UsbAccessory usbAccessory, Network network, String ipAddress) {
            this.usbAccessory = usbAccessory;
            this.network = network;
            this.ipAddress = ipAddress;

            this.handlerThread = new HandlerThread("AA Connection");
            this.handlerThread.start();
            this.asyncHandler = new Handler(handlerThread.getLooper());
        }

        public void connect() {
            this.asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    doConnect();
                }
            });
        }

        private void doConnect() {
            try {
                Log.d(TAG, "Connecting via USB to HU");
                UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                ParcelFileDescriptor fileDescriptor = usbManager.openAccessory(usbAccessory);
                if (fileDescriptor == null) {
                    throw new IllegalArgumentException("Accessory not found!");
                }
                FileDescriptor fd = fileDescriptor.getFileDescriptor();
                InputStream huInputStream = new FileInputStream(fd);
                OutputStream huOutputStream = new FileOutputStream(fd);

                Log.d(TAG, "HU connected, connecting to phone");
                phoneSocket = network.getSocketFactory().createSocket(ipAddress, 5277);
                InputStream phoneInputStream = phoneSocket.getInputStream();
                OutputStream phoneOutputStream = phoneSocket.getOutputStream();
                Log.d(TAG, "Connected to phoneNetwork");

                usbToWifiPipe = new Pipe(huInputStream, phoneOutputStream, this);
                wifiToUSBPipe = new Pipe(phoneInputStream, huOutputStream, this);

                usbToWifiPipe.start();
                wifiToUSBPipe.start();

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            notificationManager.notify(1, createNotification("Android auto connected"));
                        }
                    }
                });
            } catch (Exception e) {
                Log.d(TAG, "Could not connect", e);
                onError(e);
            }
        }

        @Override
        public void close() {
            running = false;
            asyncHandler.removeCallbacks(reconnectRunnable);
            asyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Closing connection");
                    cleanup();
                }
            });
            handlerThread.quitSafely();
        }

        @Override
        public void onError(final Exception e) {
            cleanup();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (running) {
                        notificationManager.notify(1, createNotification("Error, reconnecting: " + e.getMessage()));
                    }
                }
            });
            asyncHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY);
        }

        private void cleanup() {
            closeQuietly(usbToWifiPipe);
            closeQuietly(wifiToUSBPipe);

            closeQuietly(phoneSocket);
        }
    }

    private static class Pipe extends Thread implements Closeable {
        private ErrorListener errorListener;

        private InputStream inputStream;
        private OutputStream outputStream;

        private byte[] buffer = new byte[65536];
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
                }
                Log.d("AAGateway", "Pipe stopped reading");
            } catch (IOException e) {
                Log.e("AAGateway", "Pipe error", e);
                if (running) {
                    errorListener.onError(e);
                }
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
