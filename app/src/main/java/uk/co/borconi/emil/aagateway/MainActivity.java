package uk.co.borconi.emil.aagateway;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

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
        Intent i = new Intent(this, HackerService.class);

        if (paramIntent.getAction() != null && paramIntent.getAction().equalsIgnoreCase("android.hardware.usb.action.USB_ACCESSORY_DETACHED")) {
            Log.d("AAG", "USB DISCONNECTED");
            stopService(i);
            finish();
        } else if (paramIntent.getAction() != null && paramIntent.getAction().equalsIgnoreCase("android.hardware.usb.action.USB_ACCESSORY_ATTACHED")) {
            if (paramIntent.getParcelableExtra("accessory") != null) {
                i.putExtra("accessory", paramIntent.getParcelableExtra("accessory"));
                startService(i);
                finish();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent paramIntent) {
        Log.i("MainActivity", "Got new intent: " + paramIntent);
        super.onNewIntent(paramIntent);
        setIntent(paramIntent);
    }


}
