package com.snirpoapps.aausbtowifi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String PREF_PHONE_IP_ADDRESS = "phoneIpAdress";
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
                Intent serviceIntent = new Intent(MainActivity.this, ConnectionService.class);
                serviceIntent.setAction(ConnectionService.ACTION_START);
                serviceIntent.putExtra("ipAddress", editTextIpAddress.getText().toString());
                Toast.makeText(MainActivity.this, "Starting Android Auto proxy", Toast.LENGTH_LONG).show();
                startService(serviceIntent);
            }
        });

        buttonStopService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(MainActivity.this, ConnectionService.class);
                Toast.makeText(MainActivity.this, "Stopping Android Auto proxy", Toast.LENGTH_LONG).show();
                stopService(serviceIntent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcpInfo = wifi.getDhcpInfo();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String phoneIpAdress = prefs.getString(PREF_PHONE_IP_ADDRESS, intToIp(dhcpInfo.gateway));
        editTextIpAddress.setText(phoneIpAdress);
    }

    @Override
    protected void onPause() {
        super.onPause();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putString(PREF_PHONE_IP_ADDRESS, editTextIpAddress.getText().toString())
                .apply();
    }

    private static String intToIp(int addr) {
        return ((addr & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                (addr >>> 8 & 0xFF));
    }
}
