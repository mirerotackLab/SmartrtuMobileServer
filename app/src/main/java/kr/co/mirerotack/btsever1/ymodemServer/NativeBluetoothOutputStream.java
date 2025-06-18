package kr.co.mirerotack.btsever1.ymodemServer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import kr.co.mirerotack.btsever1.NativeBtServer;

public class NativeBluetoothOutputStream extends OutputStream {
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        NativeBtServer.nativeSend(Arrays.copyOfRange(b, off, off + len), len);  // JNI 호출
    }
    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b});
    }
}