package kr.co.mirerotack.btsever1.ymodemOverTcp;

import java.io.InputStream;
import java.io.OutputStream;

public interface StreamProvider {
    InputStream getInputStream();
    OutputStream getOutputStream();
}