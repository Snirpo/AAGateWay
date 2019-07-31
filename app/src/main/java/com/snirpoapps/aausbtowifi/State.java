package com.snirpoapps.aausbtowifi;

import java.io.Closeable;
import java.io.IOException;

public interface State extends Closeable {
    State EMPTY = new State() {
        @Override
        public void initialize() {
        }

        @Override
        public void close() throws IOException {
        }
    };

    void initialize();
}
