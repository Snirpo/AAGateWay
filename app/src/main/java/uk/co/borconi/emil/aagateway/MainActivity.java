package uk.co.borconi.emil.aagateway;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AAGateWay";
    private EditText editTextIpAddress;
    private Button buttonStartService;
    private Button buttonStopService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextIpAddress = findViewById(R.id.editTextIpAddress);
        buttonStartService = findViewById(R.id.buttonStartService);
        buttonStopService = findViewById(R.id.buttonStopService);

        buttonStartService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(MainActivity.this, HackerService.class);
                serviceIntent.putExtra("ipAddress", editTextIpAddress.getText().toString());
                Toast.makeText(MainActivity.this, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
                startService(serviceIntent);
            }
        });

        buttonStopService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(MainActivity.this, HackerService.class);
                serviceIntent.putExtra("ipAddress", editTextIpAddress.getText().toString());
                Toast.makeText(MainActivity.this, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
                stopService(serviceIntent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent paramIntent = getIntent();
        Intent serviceIntent = new Intent(this, HackerService.class);
        if ("android.hardware.usb.action.USB_ACCESSORY_DETACHED".equalsIgnoreCase(paramIntent.getAction())) {
            Toast.makeText(this, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
            stopService(serviceIntent);
        } else if ("android.hardware.usb.action.USB_ACCESSORY_ATTACHED".equalsIgnoreCase(paramIntent.getAction())) {
            Toast.makeText(this, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
            serviceIntent.putExtra("accessory", paramIntent.getParcelableExtra("accessory"));
            startService(serviceIntent);
        }
    }

    @Override
    protected void onNewIntent(Intent paramIntent) {
        Log.i("MainActivity", "Got new intent: " + paramIntent);
        super.onNewIntent(paramIntent);
        setIntent(paramIntent);
    }


}
