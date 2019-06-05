package com.snirpoapps.aausbtowifi;

public class Utils {
    public static String intToIp(int addr) {
        return ((addr & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                ((addr >>>= 8) & 0xFF) + "." +
                (addr >>> 8 & 0xFF));
    }
}
