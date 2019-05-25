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
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;


import static android.app.NotificationManager.IMPORTANCE_HIGH;


/**
 * Created by Emil on 25/03/2018.
 */

public class HackerService extends Service {
    private static final String TAG = "AAGateWay";
    private NotificationManager mNotificationManager;
    private Intent notificationIntent;
    private final IBinder mBinder = new LocalBinder();
    private UsbAccessory mAccessory;
    private UsbManager mUsbManager;
    private ParcelFileDescriptor mFileDescriptor;
    private FileDescriptor fd;
    private FileOutputStream phoneOutputStream;
    private FileInputStream phoneInputStream;


    private static Socket socket;
    private boolean running = false;
    private boolean localCompleted, usbCompleted;
    byte[] readbuffer = new byte[16384];
    private Thread tcpreader;
    private boolean connectionOK;

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
        mAccessory = intent.getParcelableExtra("accessory");
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mFileDescriptor = mUsbManager.openAccessory(mAccessory);
        if (mFileDescriptor != null) {
            fd = mFileDescriptor.getFileDescriptor();
            phoneInputStream = new FileInputStream(fd);
            phoneOutputStream = new FileOutputStream(fd);
            usbCompleted = false;
        }

        if (running)
            return START_STICKY;

        //Manually start AA.
        running = true;
        Thread usbreader = new Thread(new usbpollthread());
        tcpreader = new Thread(new AAThread());
        running = true;
        usbreader.start();
        tcpreader.start();

        return START_STICKY;
    }

    private static String intToIp(int addr) {
        return ((addr & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF));
    }

    private class AAThread implements Runnable {
        private OutputStream output;
        private InputStream input;

        public void run() {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wifi.getDhcpInfo();

            try {
                Log.d("HU", "Connecting to phone...");
                Socket socket = new Socket(intToIp(d.gateway), 5277);
                output = socket.getOutputStream();
                input = socket.getInputStream();
                output.write(new byte[]{0, 3, 0, 6, 0, 1, 0, 1, 0, 2});
                output.flush();
                input.read(new byte[12]);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }

            byte buf[] = new byte[16384];
            try {
                if (!usbCompleted) {
                    phoneInputStream.read(buf);
                    phoneOutputStream.write(new byte[]{0, 3, 0, 8, 0, 2, 0, 1, 0, 4, 0, 0});
                    //tcpreader.join();
                    usbCompleted = true;
                }
                while (!localCompleted) {
                    Log.d("HU", "Waiting for local");
                    Thread.sleep(100);
                }

                x = phoneInputStream.read(buf);
                processCarMessage(Arrays.copyOf(buf, x));
            } catch (Exception e) {
                Log.e(TAG, "Error reading: " + e.getMessage());
                if (connectionOK) {
                    running = false;
                    stopSelf();
                }
            }

            while (true) {
                getLocalmessage(false);
            }
        }

    }

    private void getLocalmessage(boolean canBeEmpty) throws IOException {


        int enc_len;
        socketinput.readFully(readbuffer, 0, 4);
        int pos = 4;
        enc_len = (readbuffer[2] & 0xFF) << 8 | (readbuffer[3] & 0xFF);
        if ((int) readbuffer[1] == 9)   //Flag 9 means the header is 8 bytes long (read it in a separate byte array)
        {
            pos += 4;
            socketinput.readFully(readbuffer, 4, 4);
        }

        socketinput.readFully(readbuffer, pos, enc_len);
        phoneOutputStream.write(Arrays.copyOf(readbuffer, enc_len + pos));


    }


    private void processCarMessage(final byte[] buf) throws IOException {
        connectionOK = true;
        socketoutput.write(buf);
    }


    @Override
    public void onDestroy() {
        running = false;
        mNotificationManager.cancelAll();
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

}
