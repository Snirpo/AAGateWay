package uk.co.borconi.emil.aagateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

public class HackerService extends Service {
    private static final String TAG = "AAGateWay";
    private NotificationManager mNotificationManager;
    private final IBinder mBinder = new LocalBinder();

    private Connector connector;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class LocalBinder extends Binder {
        HackerService getService() {
            return HackerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        String CHANNEL_ONE_ID = "uk.co.borconi.emil.aagateway";
        String CHANNEL_ONE_NAME = "Channel One";

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

        Notification.Builder notification = new Notification.Builder(this)
                .setContentTitle("Android Auto GateWay")
                .setContentText("Running....")
                .setSmallIcon(R.drawable.aawifi)
                .setTicker("");
        if (Build.VERSION.SDK_INT >= 26)
            notification.setChannelId(CHANNEL_ONE_ID);

        startForeground(1, notification.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d("AAGateWay", "Service Started");
        super.onStartCommand(intent, flags, startId);
        UsbAccessory usbAccessory = intent.getParcelableExtra("accessory");

        final Handler handler = new Handler(Looper.getMainLooper());
        connector = new Connector(usbAccessory, new ErrorListener() {
            @Override
            public void onError(Exception e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // doe iets
                    }
                });
            }
        });
        connector.start();

        return START_STICKY;
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private class Connector extends Thread {
        private final UsbAccessory usbAccessory;

        private Pipe usbToWifiPipe;
        private Pipe wifiToUSBPipe;

        private ErrorListener errorListener;

        public Connector(UsbAccessory usbAccessory, ErrorListener errorListener) {
            this.usbAccessory = usbAccessory;
            this.errorListener = errorListener;
        }

        @Override
        public void run() {
            try {
                UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                ParcelFileDescriptor fileDescriptor = usbManager.openAccessory(usbAccessory);
                if (fileDescriptor == null) {
                    throw new IllegalArgumentException("Accesory not found!");
                }
                FileDescriptor fd = fileDescriptor.getFileDescriptor();

                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                DhcpInfo d = wifi.getDhcpInfo();

                Socket socket = new Socket(intToIp(d.gateway), 5277);

                //usbInputStream.read(buf);
                try (
                        InputStream usbInputStream = new FileInputStream(fd);
                        OutputStream usbOutputStream = new FileOutputStream(fd);
                        OutputStream wifiOutputStream = socket.getOutputStream();
                        InputStream wifiInputStream = socket.getInputStream()
                ) {
//                    wifiOutputStream.write(new byte[]{0, 3, 0, 6, 0, 1, 0, 1, 0, 2});
//                    wifiOutputStream.flush();
//                    wifiInputStream.read(new byte[12]);
//
//                    usbOutputStream.write(new byte[]{0, 3, 0, 8, 0, 2, 0, 1, 0, 4, 0, 0});

                    usbToWifiPipe = new Pipe(usbInputStream, wifiOutputStream, errorListener);
                    wifiToUSBPipe = new Pipe(wifiInputStream, usbOutputStream, errorListener);

                    usbToWifiPipe.start();
                    wifiToUSBPipe.start();
                }
            } catch (Exception e) {
                errorListener.onError(e);
            }
        }

        public void kill() {
            if (usbToWifiPipe != null) usbToWifiPipe.kill();
            if (wifiToUSBPipe != null) wifiToUSBPipe.kill();
        }
    }

    private static class Pipe extends Thread {
        private ErrorListener errorListener;

        private InputStream inputStream;
        private OutputStream outputStream;

        private byte[] buffer = new byte[16384];
        private boolean running = true;

        public Pipe(InputStream inputStream, OutputStream outputStream, ErrorListener errorListener) {
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.errorListener = errorListener;
        }

        @Override
        public void run() {
            int read;
            try {
                while (running && (read = inputStream.read(buffer)) > -1) {
                    outputStream.write(buffer, 0, read);
                }
            } catch (IOException e) {
                if (running) {
                    errorListener.onError(e);
                    kill();
                }
            }
        }

        public void kill() {
            running = false;
            try {
                inputStream.close();
            } catch (IOException e) {
                // ignore
            }
            try {
                outputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

//    private void getLocalmessage(boolean canBeEmpty) throws IOException {
//
//
//        int enc_len;
//        socketinput.readFully(readbuffer, 0, 4);
//        int pos = 4;
//        enc_len = (readbuffer[2] & 0xFF) << 8 | (readbuffer[3] & 0xFF);
//        if ((int) readbuffer[1] == 9)   //Flag 9 means the header is 8 bytes long (read it in a separate byte array)
//        {
//            pos += 4;
//            socketinput.readFully(readbuffer, 4, 4);
//        }
//
//        socketinput.readFully(readbuffer, pos, enc_len);
//        usbOutputStream.write(Arrays.copyOf(readbuffer, enc_len + pos));
//
//
//    }


    @Override
    public void onDestroy() {
        mNotificationManager.cancelAll();
        connector.kill();
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        String aux = new String(hexChars);
        // Log.d("AAGateWay","ByteTohex: " + aux);
        return aux;
    }

    private static String intToIp(int addr) {
        return ((addr & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF));
    }

}
