package com.snirpoapps.aausbtowifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.Toast;

public class USBBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, ConnectionService.class);
        if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equals(intent.getAction())) {
            Toast.makeText(context, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
            context.stopService(serviceIntent);
        } else if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equals(intent.getAction())) {
            Toast.makeText(context, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
            serviceIntent.setAction(ConnectionService.ACTION_START);
            serviceIntent.putExtra("ipAddress", Utils.determinePhoneIpAddress(context));
            serviceIntent.putExtra("accessory", intent.getParcelableExtra("accessory"));
            context.startService(serviceIntent);
        } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            if (ConnectivityManager.TYPE_WIFI == netInfo.getType()) {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wifiManager.getConnectionInfo();
                String ssid = info.getSSID();
            }
        }
    }
}
