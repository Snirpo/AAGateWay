package com.snirpoapps.aausbtowifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class USBBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, ConnectionService.class);
        if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equals(intent.getAction())) {
            serviceIntent.putExtra("accessory", intent.getParcelableExtra("accessory"));
            context.startService(serviceIntent);
        }
    }
}
