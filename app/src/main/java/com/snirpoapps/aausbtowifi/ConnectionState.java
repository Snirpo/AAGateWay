package com.snirpoapps.aausbtowifi;

public class ConnectionState<T> {
    private static final ConnectionState<?> DISCONNECTED = new ConnectionState<>(false, null);

    private boolean connected;
    private T data;

    private ConnectionState(boolean connected, T data) {
        this.connected = connected;
        this.data = data;
    }

    public ConnectionState<T> clone() {
        return new ConnectionState<>(connected, data);
    }

    public static <T> ConnectionState<T> connected(T data) {
        return new ConnectionState<>(true, data);
    }

    public static <T> ConnectionState<T> disconnected() {
        return (ConnectionState<T>) DISCONNECTED;
    }

    public boolean isConnected() {
        return connected;
    }

    public T getData() {
        return data;
    }
}
