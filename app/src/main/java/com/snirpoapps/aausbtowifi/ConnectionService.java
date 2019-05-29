package com.snirpoapps.aausbtowifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class ConnectionService extends Service {
    private static final String TAG = "AAGateWay";
    public static final String ACTION_START = ConnectionService.class.getName().toLowerCase() + ".action.start";
    public static final String ACTION_STOP = ConnectionService.class.getName().toLowerCase() + ".action.stop";
    private static final String CHANNEL_ONE_ID = "com.snirpoapps.aausbtowifi";
    private static final String CHANNEL_ONE_NAME = "Channel One";

    private NotificationManager mNotificationManager;
    private final IBinder mBinder = new LocalBinder();

    private Connection connection;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class LocalBinder extends Binder {
        ConnectionService getService() {
            return ConnectionService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID,
                    CHANNEL_ONE_NAME, IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            mNotificationManager.createNotificationChannel(notificationChannel);
        }

        startForeground(1, createNotification("Running..."));
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent mainIntent = PendingIntent.getActivity(this, 0, intent, 0);

        Intent playIntent = new Intent(this, ConnectionService.class);
        playIntent.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0,
                playIntent, 0);

        Notification.Builder notification = new Notification.Builder(this)
                .setContentTitle("Android Auto USB to Wifi bridge")
                .setSmallIcon(R.drawable.aawifi)
                .setContentText(text)
                .setContentIntent(mainIntent)
                .addAction(android.R.drawable.ic_delete, "Stop", stopIntent);
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
        } else if (ACTION_START.equals(intent.getAction())) {
            closeQuietly(connection);

            UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");
            String ipAddress = intent.getStringExtra("ipAddress");

            final Handler handler = new Handler(Looper.getMainLooper());
            connection = new Connection(usbAccessory, ipAddress, new ErrorListener() {
                @Override
                public void onError(final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            mNotificationManager.notify(1, createNotification(e.getMessage()));
                        }
                    });
                }
            });
            connection.start();
        } else {
            throw new RuntimeException("Invalid action " + intent.getAction());
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        closeQuietly(connection);
        connection = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            mNotificationManager.deleteNotificationChannel(CHANNEL_ONE_ID);
        }
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private class Connection extends Thread implements Closeable, ErrorListener {
        private volatile boolean running = true;

        private final UsbAccessory usbAccessory;
        private final String ipAddress;

        private ServerSocket huServerSocket;
        private Socket huSocket;
        private Socket phoneSocket;

        private Pipe usbToWifiPipe;
        private Pipe wifiToUSBPipe;

        private ErrorListener errorListener;

        public Connection(UsbAccessory usbAccessory, String ipAddress, ErrorListener errorListener) {
            this.usbAccessory = usbAccessory;
            this.ipAddress = ipAddress;
            this.errorListener = errorListener;
        }

        @Override
        public void run() {
            try {
                Log.d(TAG, "Started connecting");

                InputStream huInputStream;
                OutputStream huOutputStream;

                if (usbAccessory != null) {
                    Log.d(TAG, "Connecting via USB to HU");
                    UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                    ParcelFileDescriptor fileDescriptor = usbManager.openAccessory(usbAccessory);
                    if (fileDescriptor == null) {
                        throw new IllegalArgumentException("Accessory not found!");
                    }
                    FileDescriptor fd = fileDescriptor.getFileDescriptor();
                    huInputStream = new FileInputStream(fd);
                    huOutputStream = new FileOutputStream(fd);
                } else {
                    Log.d(TAG, "Connecting via Wifi to HU");
                    huServerSocket = new ServerSocket(5277);
                    huSocket = huServerSocket.accept();

                    huInputStream = huSocket.getInputStream();
                    huOutputStream = huSocket.getOutputStream();
                }

                Log.d(TAG, "HU connected, connecting to phone");
                phoneSocket = new Socket(ipAddress, 5277);
                InputStream phoneInputStream = phoneSocket.getInputStream();
                OutputStream phoneOutputStream = phoneSocket.getOutputStream();
                Log.d(TAG, "Connected to phone");

                usbToWifiPipe = new Pipe(huInputStream, phoneOutputStream, this);
                wifiToUSBPipe = new Pipe(phoneInputStream, huOutputStream, this);

                usbToWifiPipe.start();
                wifiToUSBPipe.start();
            } catch (Exception e) {
                Log.d("AAGateway", "Could not connect", e);
                onError(e);
            }
        }

        @Override
        public void close() throws IOException {
            Log.d("AAGateway", "Closing connection");
            running = false;

            closeQuietly(usbToWifiPipe);
            closeQuietly(wifiToUSBPipe);

            closeQuietly(huServerSocket);
            closeQuietly(huSocket);
            closeQuietly(phoneSocket);
        }

        @Override
        public void onError(Exception e) {
            if (running) errorListener.onError(e);
            closeQuietly(this);
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
