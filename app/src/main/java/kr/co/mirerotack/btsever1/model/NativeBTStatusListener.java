package kr.co.mirerotack.btsever1.model;

public interface NativeBTStatusListener {
    public void nativeOnConnected(String macAddress);

    public void nativeOnDisconnected();
}
