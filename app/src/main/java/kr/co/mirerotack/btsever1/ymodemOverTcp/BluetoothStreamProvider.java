package kr.co.mirerotack.btsever1.ymodemOverTcp;

import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class BluetoothStreamProvider implements StreamProvider {
    private final BluetoothSocket socket;

    public BluetoothStreamProvider(BluetoothSocket socket) {
        this.socket = socket;
    }

    @Override
    public InputStream getInputStream() {
        try {
            return socket.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public OutputStream getOutputStream() {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
