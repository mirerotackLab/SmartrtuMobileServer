// native_bt.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include "../include/bluetooth.h"
#include "../include/rfcomm.h"
#include "dbus/dbus.h"

#define LOG_TAG "NativeBT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 전역 소켓 Fd = 파일 디스크립터
// 변수명 앞단에 "g_"를 붙이면 다른 cpp 코드에서도 전역으로 접근할 수 있음
// 추후, 코드 리팩토링에서 코드 분리 시 사용할 듯?
int g_serverSocket = -1;
int g_clientSocket = -1;

void ba2strMac(const bdaddr_t *ba, char *str) {
    snprintf(str, 18, "%02X:%02X:%02X:%02X:%02X:%02X",
             ba->b[5], ba->b[4], ba->b[3],
             ba->b[2], ba->b[1], ba->b[0]);
}

/// 1-1. 블루투스 소켓 생성
int createServerSocket() {
    int sockFd = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (sockFd < 0) {
        LOGE("소켓 생성 실패");
    } else {
        LOGI("소켓 생성 성공");
    }
    return sockFd;
}

/// 1-2. 서버 주소 바인딩
int bindServerSocket(int g_serverSockFd) {
    struct sockaddr_rc loc_addr = {0};
    bdaddr_t any = {{0, 0, 0, 0, 0, 0}};

    loc_addr.rc_family = AF_BLUETOOTH;
    loc_addr.rc_bdaddr = any;
    loc_addr.rc_channel = (uint8_t)1;  // RFCOMM 채널은 디폴트 1번에서 27번으로 수정함

    if (bind(g_serverSockFd, (struct sockaddr *)&loc_addr, sizeof(loc_addr)) < 0) {
        LOGE("바인드 실패");
        return -1;
    } else {
        LOGI("바인드 성공");
        return 0;
    }
}

/// 1-3. 연결 대기 상태 진입
int listenOnSocket(int g_serverSockFd) {
    if (listen(g_serverSockFd, 1) < 0) {
        LOGE("리슨 실패");
        return -1;
    } else {
        LOGI("리슨 성공");
        return 0;
    }
}

/// 1-4. 클라이언트 수락
int acceptClient(int g_serverSockFd, struct sockaddr_rc* rem_addr) {
    socklen_t opt = sizeof(struct sockaddr_rc);
    int client = accept(g_serverSockFd, (struct sockaddr *)rem_addr, &opt);
    if (client < 0) {
        LOGE("클라이언트 수락 실패");
    } else {
        char addr[18] = {0};
        ba2strMac(&rem_addr->rc_bdaddr, addr);
        LOGI("클라이언트 연결됨: %s", addr);
    }
    return client;
}


/// 1. 기본 RFCOMM 소켓 생성 및 accept 대기 및 연결 수락
extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_MainActivity_00024NativeBtServer_createBluetoothServer(JNIEnv *env, jobject thiz) {
    g_serverSocket = createServerSocket();
    if (g_serverSocket < 0) return -1;

    if (bindServerSocket(g_serverSocket) < 0) {
        close(g_serverSocket);
        return -2;
    }

    if (listenOnSocket(g_serverSocket) < 0) {
        close(g_serverSocket);
        return -3;
    }

    LOGI("클라이언트 연결 대기 중...");

    struct sockaddr_rc rem_addr = {0};

    g_clientSocket = acceptClient(g_serverSocket, &rem_addr);
    if (g_clientSocket < 0) {
        close(g_serverSocket);
        return -4;
    }

    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeRead(JNIEnv *env, jobject thiz, jbyteArray buffer) {
    if (g_clientSocket < 0) return -1;

    jbyte* nativeBuf = env->GetByteArrayElements(buffer, nullptr);
    int bytes = read(g_clientSocket, nativeBuf, 1024);
    env->ReleaseByteArrayElements(buffer, nativeBuf, 0);
    return bytes;
}

extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeWrite(JNIEnv *env, jobject thiz, jbyteArray buffer, jint length) {
    if (g_clientSocket < 0) return -1;

    jbyte* nativeBuf = env->GetByteArrayElements(buffer, nullptr);
    int sent = write(g_clientSocket, nativeBuf, length);
    env->ReleaseByteArrayElements(buffer, nativeBuf, JNI_ABORT);  // 커밋 불필요
    return sent;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeIsConnected(JNIEnv *env, jobject thiz) {
    return g_clientSocket >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeClose(JNIEnv *env, jobject thiz) {
    if (g_clientSocket >= 0) {
        close(g_clientSocket);
        g_clientSocket = -1;
        LOGI("클라이언트 소켓 닫힘");
    }
    if (g_serverSocket >= 0) {
        close(g_serverSocket);
        g_serverSocket = -1;
        LOGI("서버 소켓 닫힘");
    }
}


