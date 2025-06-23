// native_bt.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <pthread.h>
#include <asm-generic/ioctls.h>
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

// 연결 감지 CallBack Listener (Java listener 객체 백업)
static jobject g_listenerObj = nullptr;                   // Java에서 전달 받은 Listener 객체를 JNI 내부에 저장
static jmethodID g_onClientConnectedMethod = nullptr;     // Java Listener 객체 내의 onClientConnected(String) 메서드 ID(주소) 저장

// 연결 해제 CallBack Listener
static jmethodID g_onClientDisconnectedMethod = nullptr;  //

struct timeval tv;

extern "C"
JNIEXPORT void JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_setListener(JNIEnv *env, jclass clazz, jobject listener) {
    // 기존에 저장된 리스너 객체가 있다면 메모리 해제
    if (g_listenerObj) {
        env->DeleteGlobalRef(g_listenerObj);  // 기존 리스너 제거
    }

    // 새로 받은 리스너 객체를 전역(Global) 참조로 저장 (NewGlobalRef로 저장하지 않으면 GC에 의해 수거됨)
    g_listenerObj = env->NewGlobalRef(listener);

    // 전달된 listener 객체의 클래스 정보를 얻음
    jclass cls = env->GetObjectClass(listener);

    // 연결 콜백
    g_onClientConnectedMethod = env->GetMethodID(cls, "nativeOnConnected", "(Ljava/lang/String;)V");
    // 메서드 ID를 찾지 못했을 경우 로그 출력
    if (!g_onClientConnectedMethod) {
        LOGE("onClientConnected 메서드 찾기 실패");
    }

    // 🔧 연결 해제 콜백
    g_onClientDisconnectedMethod = env->GetMethodID(cls, "nativeOnDisconnected", "()V");
    if (!g_onClientDisconnectedMethod) {
        LOGE("onClientDisconnected 메서드 찾기 실패");
    }
}


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

    tv.tv_sec = 0;         // 초 단위 대기 시간 (0초)
    tv.tv_usec = 100000;   // 마이크로초 단위 대기 시간 (100ms)

    return sockFd;
}

/// 1-2. 서버 주소 바인딩
int bindServerSocket(int g_serverSockFd) {
    struct sockaddr_rc loc_addr = {0};
    bdaddr_t any = {{0, 0, 0, 0, 0, 0}};

    loc_addr.rc_family = AF_BLUETOOTH;
    loc_addr.rc_bdaddr = any;
    loc_addr.rc_channel = (uint8_t)1;

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
void acceptClient(JNIEnv* env, int g_serverSockFd, struct sockaddr_rc* rem_addr) {
    socklen_t opt = sizeof(struct sockaddr_rc);
    g_clientSocket = accept(g_serverSockFd, (struct sockaddr *)rem_addr, &opt);
    if (g_clientSocket < 0) {
        LOGE("클라이언트 수락 실패");
    } else {
        char addr[18] = {0};
        ba2strMac(&rem_addr->rc_bdaddr, addr);
        LOGI("클라이언트 연결됨: %s", addr);

        // 아래는 Java로 클라이언트가 연결됐다는 것을 CallBack 하는 로직

        // Java 문자열 객체 생성: addr(MacAddress)을 Java의 String 객체로 변환
        jstring jmac = env->NewStringUTF(addr);  // C 문자열 → Java String 변환 (MAC 주소)

        // Java에 등록된 리스너 객체에서 nativeOnConnected(String) 메서드를 호출
        // 전달값: 위에서 만든 jmac (MAC 주소 문자열)
        env->CallVoidMethod(g_listenerObj, g_onClientConnectedMethod, jmac);  // Java 콜백 호출

        // JNI 지역 참조 해제: jmac 문자열 객체를 GC 가능 상태로 만듦 (메모리 누수 방지)
        env->DeleteLocalRef(jmac);  // JNI 지역 메모리 해제
    }
}

/// 1. 기본 RFCOMM 소켓 생성 및 accept 대기 및 연결 수락
extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_createBluetoothServer(JNIEnv *env, jclass clazz) {
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

    // g_clientSocket 초기화
    acceptClient(env, g_serverSocket, &rem_addr);

    if (g_clientSocket < 0) {
        LOGE("클라이언트 수락 실패");
        close(g_serverSocket);
        return -4;
    }

    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_closeBluetoothServer(JNIEnv *env, jclass clazz) {
    if (g_clientSocket >= 0) {
        shutdown(g_clientSocket, SHUT_RDWR);
        close(g_clientSocket);
        g_clientSocket = -1;
        LOGI("클라이언트 소켓 닫힘");
    }
    if (g_serverSocket >= 0) {
        shutdown(g_serverSocket, SHUT_RDWR);
        close(g_serverSocket);
        g_serverSocket = -1;
        LOGI("서버 소켓 닫힘");
    }
}

/// 클라이언트에 의해서 연결이 종료 되었는지 체크하고, 상태 동기화 (Off 상태면, 서버도 닫음)
void checkAndHandleDisconnect(JNIEnv* env, int result) {
    if (result <= 0) {
        if (g_listenerObj && g_onClientDisconnectedMethod) {
            env->CallVoidMethod(g_listenerObj, g_onClientDisconnectedMethod);
        }

        if (g_clientSocket >= 0) {
            close(g_clientSocket);
            g_clientSocket = -1;
            LOGI("클라이언트 소켓 종료됨");
        }
    }
}

#define LOG_TAG "NativeBt"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static volatile bool g_receiving = false;
static pthread_t g_recvThread;

extern JavaVM* g_vm; // Java callback 위해 필요 (필요 시 설정)

// 읽기 루프 (별도 스레드에서 실행)
void* recvLoop(void*) {
    const int bufferSize = 512;
    char buf[bufferSize];
    int packetCount = 0;

    while (g_receiving) {
        int bytes = read(g_clientSocket, buf, bufferSize);
        if (bytes <= 0) {
            LOGE("read 실패 또는 연결 종료: %s", strerror(errno));
            break;
        }

        // TODO: 여기서 받은 데이터 처리 (파일 저장, CRC 검사 등)

        if (++packetCount % 10 == 0) {
            usleep(1000); // 10패킷마다 1ms sleep → 부하 분산
        }
    }

    g_receiving = false;
    LOGE("수신 루프 종료됨");
    return nullptr;
}

// Java → JNI: 수신 시작
extern "C"
JNIEXPORT void JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeStartReceiving(JNIEnv* env, jclass clazz) {
    if (g_clientSocket < 0 || g_receiving) return;

    g_receiving = true;
    pthread_create(&g_recvThread, nullptr, recvLoop, nullptr);
}


// 입력 받은 Buffer Array에 직접 데이터를 채워주고, 그 사이즈 만큼 개수 반환
extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeRead(JNIEnv *env, jclass clazz, jbyteArray buffer) {
    jint length = env->GetArrayLength(buffer);
    LOGI("nativeRead 호출, 수신할 데이터 크기 : %d", length);

    if (g_clientSocket < 0) {
        LOGE("클라이언트 연결 상태 OFF");
        return -1;
    }
    usleep(1000); // 1ms

    jbyte* nativeBuf = env->GetByteArrayElements(buffer, nullptr);

    int bytes = read(g_clientSocket, nativeBuf, length);
    env->ReleaseByteArrayElements(buffer, nativeBuf, 0);
    checkAndHandleDisconnect(env, bytes);

    return bytes;
}

extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeSend(JNIEnv *env, jclass clazz, jbyteArray buffer, jint length) {
    LOGI("nativeRead 호출, 송신할 데이터 크기 : %d", length);

    if (g_clientSocket < 0) {
        LOGE("클라이언트 연결 상태 OFF");
        return -1;
    }
    usleep(1000); // 1ms

    jbyte* nativeBuf = env->GetByteArrayElements(buffer, nullptr);
    int sent = write(g_clientSocket, nativeBuf, length);
    env->ReleaseByteArrayElements(buffer, nativeBuf, JNI_ABORT);

    checkAndHandleDisconnect(env, sent);
    return sent;
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeIsConnected(JNIEnv *env, jclass clazz) {
    return (g_clientSocket >= 0) ? JNI_TRUE : JNI_FALSE;
}

// 현재 수신 대기 상태의 바이트 수를 반환함
extern "C"
JNIEXPORT jint JNICALL
Java_kr_co_mirerotack_btsever1_NativeBtServer_nativeAvailable(JNIEnv *env, jclass clazz) {
    if (g_clientSocket < 0) {
        LOGE("nativeAvailable: 소켓이 닫혀 있음");
        return 0;
    }

    int count = 0;
    int result = ioctl(g_clientSocket, FIONREAD, &count);
    if (result == -1) {
        LOGE("nativeAvailable: ioctl 실패 - errno: %d", errno);
        return 0;
    }

    // LOGI("nativeAvailable: 수신 가능 바이트 수 = %d", count);
    return count;
}