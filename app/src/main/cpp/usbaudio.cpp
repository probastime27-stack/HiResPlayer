// Minimal USB Audio Class isochronous output over usbfs (bypasses the Android mixer).
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <algorithm>
#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

namespace {
constexpr int NURB = 8;
int fd = -1, ep = 0, rate = 0, ch = 2, sub = 3, maxPkt = 0, inMode = 0, pps = 1000, ppu = 8;
size_t frameB = 0, rcap = 0, rhead = 0, rtail = 0, rfill = 0;
long long acc = 0, realFr[NURB];
long long written = 0, taken = 0, inflight = 0;  // guarded by mu
std::vector<uint8_t> ring;
std::mutex mu;
std::atomic<bool> playing{false}, stopFlag{false};
std::thread th;
usbdevfs_urb* urbs[NURB];
uint8_t* bufs[NURB];
const int inBytes[4] = {2, 3, 4, 4};  // 16-bit, 24-bit packed, 32-bit, float

void ringPut(const uint8_t* s, size_t n) { for (size_t k = 0; k < n; k++) { ring[rtail] = s[k]; rtail = (rtail + 1) % rcap; } rfill += n; }
void ringGet(uint8_t* d, size_t n) { for (size_t k = 0; k < n; k++) { d[k] = ring[rhead]; rhead = (rhead + 1) % rcap; } rfill -= n; }

int32_t toS32(const uint8_t* p, int mode) {
  switch (mode) {
    case 0: { int16_t v; memcpy(&v, p, 2); return (int32_t)((uint32_t)(uint16_t)v << 16); }
    case 1: return (int32_t)(((uint32_t)p[0] << 8) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 24));
    case 2: { int32_t v; memcpy(&v, p, 4); return v; }
    default: { float f; memcpy(&f, p, 4); if (f > 1.f) f = 1.f; if (f < -1.f) f = -1.f; return (int32_t)((double)f * 2147483647.0); }
  }
}

void fillUrb(int i) {
  std::lock_guard<std::mutex> g(mu);
  usbdevfs_urb* u = urbs[i]; uint8_t* b = bufs[i]; size_t off = 0; long long real = 0;
  for (int p = 0; p < ppu; p++) {
    acc += rate; int fr = (int)(acc / pps); acc -= (long long)fr * pps;
    size_t bytes = (size_t)fr * frameB, take = 0;
    if (playing) take = std::min((size_t)fr, rfill / frameB);
    if (take) { ringGet(b + off, take * frameB); real += take; }
    if (take < (size_t)fr) memset(b + off + take * frameB, 0, bytes - take * frameB);
    u->iso_frame_desc[p].length = bytes; u->iso_frame_desc[p].actual_length = 0; u->iso_frame_desc[p].status = 0;
    off += bytes;
  }
  u->type = USBDEVFS_URB_TYPE_ISO; u->endpoint = ep; u->status = 0; u->flags = USBDEVFS_URB_ISO_ASAP;
  u->buffer = b; u->buffer_length = (int)off; u->number_of_packets = ppu; u->usercontext = (void*)(intptr_t)i;
  realFr[i] = real; taken += real; inflight += real;
}

void loop() {
  int live = 0;
  for (int i = 0; i < NURB; i++) { fillUrb(i); if (ioctl(fd, USBDEVFS_SUBMITURB, urbs[i]) == 0) live++; }
  while (live > 0) {
    usbdevfs_urb* r = nullptr;
    if (ioctl(fd, USBDEVFS_REAPURB, &r) < 0) { if (errno == EINTR) continue; break; }
    int i = (int)(intptr_t)r->usercontext;
    { std::lock_guard<std::mutex> g(mu); inflight -= realFr[i]; realFr[i] = 0; }
    if (stopFlag) { live--; continue; }
    fillUrb(i);
    if (ioctl(fd, USBDEVFS_SUBMITURB, r) < 0) live--;
  }
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_hiresplayer_NativeUsb_open(
    JNIEnv*, jobject, jint fd_, jint ep_, jint mp, jint rate_, jint ch_, jint sub_, jint mode, jint interval) {
  fd = fd_; ep = ep_; maxPkt = mp; rate = rate_; ch = ch_; sub = sub_; inMode = mode;
  int sp = ioctl(fd, USBDEVFS_GET_SPEED);
  int units = sp >= 3 ? 8000 : 1000;
  pps = std::max(1, units >> (interval > 0 ? interval - 1 : 0));
  ppu = std::max(1, pps / 125);
  frameB = (size_t)ch * sub;
  if ((size_t)((rate + pps - 1) / pps) * frameB > (size_t)maxPkt) return JNI_FALSE;
  rcap = (size_t)rate / 2 * frameB; ring.assign(rcap, 0); rhead = rtail = rfill = 0;
  written = taken = inflight = 0; acc = 0; memset(realFr, 0, sizeof realFr);
  playing = false; stopFlag = false;
  for (int i = 0; i < NURB; i++) {
    urbs[i] = (usbdevfs_urb*)calloc(1, sizeof(usbdevfs_urb) + ppu * sizeof(usbdevfs_iso_packet_desc));
    bufs[i] = (uint8_t*)calloc(ppu, maxPkt);
  }
  th = std::thread(loop);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL Java_com_example_hiresplayer_NativeUsb_write(JNIEnv* e, jobject, jbyteArray a, jint n) {
  jbyte* p = e->GetByteArrayElements(a, nullptr);
  size_t inF = (size_t)inBytes[inMode] * ch, frames = (size_t)n / inF;
  {
    std::lock_guard<std::mutex> g(mu);
    frames = std::min(frames, (rcap - rfill) / frameB);
    uint8_t out[16];
    for (size_t f = 0; f < frames; f++) {
      for (int c = 0; c < ch; c++) {
        int32_t v = toS32((const uint8_t*)p + (f * ch + c) * inBytes[inMode], inMode);
        uint32_t u = (uint32_t)v >> (32 - 8 * sub);
        for (int s = 0; s < sub; s++) out[c * sub + s] = (u >> (8 * s)) & 0xFF;
      }
      ringPut(out, frameB);
    }
    written += frames;
  }
  e->ReleaseByteArrayElements(a, p, JNI_ABORT);
  return (jint)(frames * inF);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_hiresplayer_NativeUsb_setPlaying(JNIEnv*, jobject, jboolean b) { playing = b; }

extern "C" JNIEXPORT void JNICALL Java_com_example_hiresplayer_NativeUsb_flush(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> g(mu);
  rhead = rtail = rfill = 0; written = taken = inflight = 0; memset(realFr, 0, sizeof realFr);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_hiresplayer_NativeUsb_position(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> g(mu); return std::max(0LL, taken - inflight);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_hiresplayer_NativeUsb_drained(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> g(mu); return written <= taken - inflight;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_hiresplayer_NativeUsb_close(JNIEnv*, jobject) {
  if (!th.joinable()) return;
  stopFlag = true;
  for (int i = 0; i < NURB; i++) if (urbs[i]) ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]);
  th.join();
  for (int i = 0; i < NURB; i++) { free(urbs[i]); free(bufs[i]); urbs[i] = nullptr; bufs[i] = nullptr; }
}
