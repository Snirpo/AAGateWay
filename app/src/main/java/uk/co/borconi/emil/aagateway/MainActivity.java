package uk.co.borconi.emil.aagateway;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AAGateWay";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent paramIntent = getIntent();
        Intent serviceIntent = new Intent(this, HackerService.class);
        if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equalsIgnoreCase(paramIntent.getAction())) {
            Toast.makeText(this, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
            stopService(serviceIntent);
            finish();
        } else if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equalsIgnoreCase(paramIntent.getAction())) {
            Toast.makeText(this, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
            serviceIntent.putExtra("accessory", paramIntent.getParcelableExtra("accessory"));
            startService(serviceIntent);
            finish();
        } else {
            Toast.makeText(this, "This app should be started from an Android Auto intent", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent paramIntent) {
        Log.i("MainActivity", "Got new intent: " + paramIntent);
        super.onNewIntent(paramIntent);
        setIntent(paramIntent);
    }


}
