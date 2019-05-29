package com.snirpoapps.aausbtowifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class USBBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, ConnectionService.class);
        if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equalsIgnoreCase(intent.getAction())) {
            Toast.makeText(context, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
            context.stopService(serviceIntent);
        } else if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equalsIgnoreCase(intent.getAction())) {
            Toast.makeText(context, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
            serviceIntent.setAction(ConnectionService.ACTION_START);
            serviceIntent.putExtra("ipAddress", Utils.determinePhoneIpAddress(context));
            serviceIntent.putExtra("accessory", intent.getParcelableExtra("accessory"));
            context.startService(serviceIntent);
        }
    }
}
