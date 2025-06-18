package kr.co.mirerotack.btsever1.ymodemServer;

import java.io.IOException;
import java.io.InputStream;

import kr.co.mirerotack.btsever1.NativeBtServer;

public class NativeBluetoothInputStream extends InputStream {
    @Override
    public int read(byte[] b) throws IOException {
        return NativeBtServer.nativeRead(b);  // JNI 호출
    }
    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int r = read(one);
        return r <= 0 ? -1 : one[0] & 0xFF;
    }
}
