package com.snirpoapps.aausbtowifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposables;

public class ObservableUtils {
    public static Observable<Intent> registerReceiver(final Context context, String action) {
        return registerReceiver(context, new IntentFilter(action));
    }

    public static Observable<Intent> registerReceiver(final Context context, final IntentFilter intentFilter) {
        final Context appContext = context.getApplicationContext();
        return Observable.create((ObservableOnSubscribe<Intent>) emitter -> {
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context1, final Intent intent) {
                    emitter.onNext(intent);
                }
            };
            appContext.registerReceiver(receiver, intentFilter);
            emitter.setDisposable(Disposables.fromRunnable(() -> appContext.unregisterReceiver(receiver)));
        }).subscribeOn(AndroidSchedulers.mainThread());
    }

    public static Observable<ConnectionState<Network>> observeNetwork(final Context context, final NetworkRequest networkRequest) {
        final Context appContext = context.getApplicationContext();
        final ConnectivityManager connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return Observable.create((ObservableOnSubscribe<ConnectionState<Network>>) emitter -> {
            final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    emitter.onNext(ConnectionState.connected(network));
                }

                @Override
                public void onUnavailable() {
                    emitter.onNext(ConnectionState.disconnected());
                }
            };

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
            emitter.setDisposable(Disposables.fromRunnable(() -> connectivityManager.unregisterNetworkCallback(networkCallback)));
        }).subscribeOn(AndroidSchedulers.mainThread());


    }
}
