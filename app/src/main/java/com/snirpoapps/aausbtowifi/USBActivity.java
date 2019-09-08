package com.snirpoapps.aausbtowifi;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Parcelable;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class USBActivity extends AppCompatActivity {
    @Override
    protected void onResume() {
        super.onResume();
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(getIntent().getAction())) {
            Intent serviceIntent = new Intent(this, ConnectionService.class);
            serviceIntent.setAction(getIntent().getAction());
            serviceIntent.putExtra("accessory", (Parcelable) getIntent().getParcelableExtra("accessory"));
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
