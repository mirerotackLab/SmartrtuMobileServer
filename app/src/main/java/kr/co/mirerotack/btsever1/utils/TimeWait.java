package kr.co.mirerotack.btsever1.utils;

public class TimeWait {
    static void waitForSeconds(int sleepTime) {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
