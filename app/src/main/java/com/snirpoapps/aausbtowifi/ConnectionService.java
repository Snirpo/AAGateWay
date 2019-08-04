package com.snirpoapps.aausbtowifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Pair;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class ConnectionService extends Service {
    public static final String ACTION_STOP = ConnectionService.class.getName().toLowerCase() + ".action.stop";

    private static final String TAG = "AAGateWay";
    private static final String CHANNEL_ONE_ID = "com.snirpoapps.aausbtowifi";
    private static final String CHANNEL_ONE_NAME = "Channel One";

    private NotificationManager notificationManager;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private UsbManager usbManager;
    private SharedPreferences preferences;

    private PublishSubject<Intent> usbAttached$ = PublishSubject.create();
    private Disposable connectionDisposable;

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
        usbAccessoryFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        Observable<Intent> usbDetached$ = ObservableUtils.registerReceiver(this, usbAccessoryFilter);
        Observable<ConnectionState<UsbAccessory>> usb$ = Observable.merge(usbAttached$, usbDetached$)
                .scan(ConnectionState.disconnected(), (state, intent) -> {
                    UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");
                    if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
                        return ConnectionState.connected(usbAccessory);
                    } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())
                            && state.getData().equals(usbAccessory)) {
                        return ConnectionState.disconnected();
                    }
                    return state;
                });

        NetworkRequest.Builder request = new NetworkRequest.Builder();
        request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Observable<ConnectionState<Network>> network$ = ObservableUtils.observeNetwork(this, request.build())
                .scan(ConnectionState.disconnected(), (state, connectionState) -> {
                    if (connectionState.isConnected()) {
                        String phoneSSID = preferences.getString(Preferences.PHONE_SSID, "");
                        WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                        if (connectionInfo != null && phoneSSID.equals(connectionInfo.getSSID())) {
                            return ConnectionState.connected(connectionState.getData());
                        } else {
                            updateNotification("Not connected to phone SSID, please connect to correct SSID");
                        }
                    }
                    return ConnectionState.disconnected();
                });

        connectionDisposable = Observable.combineLatest(usb$, network$, Pair::create)
                .switchMapCompletable(pair -> {
                    UsbAccessory usbAccessory = pair.first.getData();
                    Network phoneNetwork = pair.second.getData();
                    String phoneIpAddress = preferences.getString(Preferences.PHONE_IP_ADDRESS, null);
                    if (phoneIpAddress == null || phoneIpAddress.isEmpty()) {
                        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                        phoneIpAddress = Utils.intToIp(dhcpInfo.gateway);
                    }
                    updateNotification("Setting up Android Auto connection...");
                    return createConnection(usbAccessory, phoneNetwork, phoneIpAddress);
                }).observeOn(Schedulers.io()).subscribe();
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
            usbAttached$.onNext(intent);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ONE_ID);
        }
        connectionDisposable.dispose();
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private Completable createConnection(UsbAccessory usbAccessory, Network network, String ipAddress) {
        return Completable.create(subscriber -> {
            try (Connection connection = new Connection(usbAccessory, network, ipAddress, subscriber::tryOnError)) {
                subscriber.setDisposable(Disposables.fromRunnable(() -> {
                    connection.close();
                    updateNotification("Android auto disconnected");
                }));
                updateNotification("Android auto connected");
            } catch (Exception e) {
                Log.d(TAG, "Could not connect", e);
                updateNotification("Could not connect: " + e.getMessage());
                throw Exceptions.propagate(e);
            }
        }).retryWhen(it -> it.delay(10000, TimeUnit.MILLISECONDS));
    }

    private class Connection implements Closeable {
        private ParcelFileDescriptor huFileDescriptor;
        private Socket phoneSocket;

        private Pipe usbToWifiPipe;
        private Pipe wifiToUSBPipe;

        public Connection(UsbAccessory usbAccessory, Network network, String ipAddress, ErrorListener errorHandler) throws IOException {
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

            usbToWifiPipe = new Pipe(huInputStream, phoneOutputStream, errorHandler);
            wifiToUSBPipe = new Pipe(phoneInputStream, huOutputStream, errorHandler);

            usbToWifiPipe.start();
            wifiToUSBPipe.start();
        }

        @Override
        public void close() {
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
