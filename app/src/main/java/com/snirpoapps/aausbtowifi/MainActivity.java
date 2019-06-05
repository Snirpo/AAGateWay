package com.snirpoapps.aausbtowifi;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {
    private static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    private SharedPreferences preferences;

    private Spinner spinnerPhoneSSID;
    private EditText editTextIpAddress;
    private Button buttonStartService;
    private Button buttonStopService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        spinnerPhoneSSID = findViewById(R.id.spinnerPhoneSSID);
        editTextIpAddress = findViewById(R.id.editTextIpAddress);
        buttonStartService = findViewById(R.id.buttonStartService);
        buttonStopService = findViewById(R.id.buttonStopService);

        buttonStartService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                Intent serviceIntent = new Intent(MainActivity.this, ConnectionService.class);
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

        EasyPermissions.requestPermissions(this, "This app needs some permissions, please accept them.", 1, PERMISSIONS);

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        List<String> networks = new ArrayList<>();
        for (WifiConfiguration config : wifiManager.getConfiguredNetworks()) {
            networks.add(config.SSID.replace("\"", ""));
        }
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, networks);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPhoneSSID.setAdapter(dataAdapter);
        spinnerPhoneSSID.setSelection(dataAdapter.getPosition(preferences.getString(Preferences.PHONE_SSID, "")));
        editTextIpAddress.setText(preferences.getString(Preferences.PHONE_IP_ADDRESS, ""));
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    private void saveSettings() {
        preferences.edit()
                .putString(Preferences.PHONE_SSID, "\"" + spinnerPhoneSSID.getSelectedItem().toString() + "\"")
                .putString(Preferences.PHONE_IP_ADDRESS, editTextIpAddress.getText().toString())
                .commit();
    }
}
