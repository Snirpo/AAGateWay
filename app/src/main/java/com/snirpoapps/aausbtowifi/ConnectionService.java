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

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class ConnectionService extends Service {
    public static final String ACTION_STOP = ConnectionService.class.getName().toLowerCase() + ".action.stop";

    private static final String TAG = "AAGateWay";
    private static final String CHANNEL_ONE_ID = "com.snirpoapps.aausbtowifi";
    private static final String CHANNEL_ONE_NAME = "Channel One";

    private NotificationManager notificationManager;
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
        usbAccessoryFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        Observable<Intent> usbDetached$ = ObservableUtils.registerReceiver(this, usbAccessoryFilter);
        Observable<ConnectionState<UsbAccessory>> usb$ = Observable.merge(this.usbAttached$, usbDetached$)
                .scan(ConnectionState.<UsbAccessory>disconnected(), (state, intent) -> {
                    UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");
                    if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
                        return ConnectionState.connected(usbAccessory);
                    } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())
                            && usbAccessory.equals(state.getData())) {
                        return ConnectionState.disconnected();
                    }
                    return state;
                }).distinctUntilChanged().doOnNext(it -> Log.d(TAG, "USB state changed: " + it));

        NetworkRequest.Builder request = new NetworkRequest.Builder();
        request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Observable<ConnectionState<Network>> network$ = ObservableUtils.observeNetwork(this, request.build())
                .map(state -> {
                    if (state.isConnected()) {
                        String phoneSSID = preferences.getString(Preferences.PHONE_SSID, "");
                        WifiInfo connectionInfo = wifiManager.getConnectionInfo();
                        if (phoneSSID.equals(connectionInfo.getSSID())) {
                            return state;
                        }
                        updateNotification("Not connected to phone SSID, please connect to correct SSID");
                    }
                    return ConnectionState.<Network>disconnected();
                }).distinctUntilChanged().doOnNext(it -> Log.d(TAG, "Network state changed: " + it));

        connectionDisposable = Observable.combineLatest(usb$, network$, Pair::create)
                .switchMapCompletable(pair -> {
                    if (pair.first.isConnected() && pair.second.isConnected()) {
                        UsbAccessory usbAccessory = pair.first.getData();
                        Network phoneNetwork = pair.second.getData();
                        String phoneIpAddress = preferences.getString(Preferences.PHONE_IP_ADDRESS, null);
                        if (phoneIpAddress == null || phoneIpAddress.isEmpty()) {
                            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                            phoneIpAddress = Utils.intToIp(dhcpInfo.gateway);
                        }
                        updateNotification("Setting up Android Auto connection...");
                        return createConnection(usbAccessory, phoneNetwork, phoneIpAddress);
                    }
                    return Completable.complete();
                }).subscribe();
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
        Log.d(TAG, "updateNotification: " + text);
        notificationManager.notify(1, createNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: " + intent);
        super.onStartCommand(intent, flags, startId);
        if (ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
        } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
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

    private Completable createConnection(UsbAccessory usbAccessory, Network network, String ipAddress) {
        return Completable.using(
                () -> new Connection(usbAccessory, network, ipAddress),
                Connection::start,
                Connection::close
        )
                .subscribeOn(Schedulers.io())
                .doOnSubscribe(it -> updateNotification("Android auto connected"))
                .doOnComplete(() -> updateNotification("Android auto disconnected"))
                .doOnError(e -> {
                    Log.e(TAG, "Connection error: ", e);
                    updateNotification("Could not connect: " + e.getMessage());
                })
                .onErrorComplete();
    }

    private class Connection implements Closeable {
        private final ParcelFileDescriptor huFileDescriptor;
        private final Socket phoneSocket;
        private final InputStream huInputStream;
        private final OutputStream huOutputStream;
        private final InputStream phoneInputStream;
        private final OutputStream phoneOutputStream;

        public Connection(UsbAccessory usbAccessory, Network network, String ipAddress) throws IOException {
            try {
                Log.d(TAG, "Connecting via USB to HU");
                huFileDescriptor = usbManager.openAccessory(usbAccessory);
                if (huFileDescriptor == null) {
                    throw new IllegalArgumentException("Accessory not found!");
                }
                FileDescriptor fileDescriptor = huFileDescriptor.getFileDescriptor();
                huInputStream = new FileInputStream(fileDescriptor);
                huOutputStream = new FileOutputStream(fileDescriptor);

                Log.d(TAG, "HU connected, connecting to phone");

                phoneSocket = network.getSocketFactory().createSocket();
                phoneSocket.connect(InetSocketAddress.createUnresolved(ipAddress, 5277), 10000);
                phoneInputStream = phoneSocket.getInputStream();
                phoneOutputStream = phoneSocket.getOutputStream();

                Log.d(TAG, "Connected to phone");
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        public Completable start() {
            return Completable.mergeArray(
                    createPipe(huInputStream, phoneOutputStream),
                    createPipe(phoneInputStream, huOutputStream)
            );
        }

        @Override
        public void close() {
            closeQuietly(huFileDescriptor);
            closeQuietly(phoneSocket);
            closeQuietly(huInputStream);
            closeQuietly(huOutputStream);
            closeQuietly(phoneInputStream);
            closeQuietly(phoneOutputStream);
        }
    }

    private static Completable createPipe(final InputStream inputStream, final OutputStream outputStream) {
        return Completable.create(emitter -> {
            byte[] buffer = new byte[16384];
            int read;
            try {
                Log.d(TAG, "Pipe started reading");
                while (!emitter.isDisposed() && (read = inputStream.read(buffer)) > -1) {
                    outputStream.write(buffer, 0, read);
                    outputStream.flush();
                }
                Log.d(TAG, "Pipe stopped reading");
            } catch (IOException e) {
                Log.e(TAG, "Pipe error", e);
                emitter.tryOnError(e);
            }
        }).subscribeOn(Schedulers.io());
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
