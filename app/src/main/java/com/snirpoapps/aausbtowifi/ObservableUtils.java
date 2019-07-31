package com.snirpoapps.aausbtowifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposables;

public class ObservableUtils {
    public static Observable<Intent> registerReceiver(final Context context, String action) {
        return registerReceiver(context, new IntentFilter(action));
    }

    public static Observable<Intent> registerReceiver(final Context context, final IntentFilter intentFilter) {
        final Context appContext = context.getApplicationContext();
        return Observable.create(new ObservableOnSubscribe<Intent>() {
            @Override
            public void subscribe(final ObservableEmitter<Intent> emitter) {
                final BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(final Context context, final Intent intent) {
                        emitter.onNext(intent);
                    }
                };
                appContext.registerReceiver(receiver, intentFilter);
                emitter.setDisposable(Disposables.fromRunnable(new Runnable() {
                    @Override
                    public void run() {
                        appContext.unregisterReceiver(receiver);
                    }
                }));
            }
        }).subscribeOn(AndroidSchedulers.mainThread());
    }
}
