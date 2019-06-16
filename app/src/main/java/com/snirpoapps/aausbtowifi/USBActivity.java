package com.snirpoapps.aausbtowifi;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

public class USBActivity extends AppCompatActivity {
    @Override
    protected void onResume() {
        super.onResume();
        if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equals(getIntent().getAction())) {
            Intent serviceIntent = new Intent(this, ConnectionService.class);
            serviceIntent.putExtra("accessory", getIntent().getParcelableExtra("accessory"));
            Toast.makeText(this, "Starting AA proxy service", Toast.LENGTH_LONG).show();
            startService(serviceIntent);
        }
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
}
