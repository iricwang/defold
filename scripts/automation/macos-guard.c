// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

// Loaded only into an owned automation engine, before its entry point.
#include <sys/event.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <pthread.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

static int g_Queue = -1;

static int AutomationEnabled(void)
{
    const char* value = getenv("DM_EDITOR_AUTOMATION");
    return value && strcmp(value, "1") == 0;
}

static void* WatchOwner(void* unused)
{
    (void)unused;
    struct kevent event;
    int result;
    do { result = kevent(g_Queue, 0, 0, &event, 1, 0); } while (result < 0 && errno == EINTR);
    // Fail closed, including an unexpected watchdog error. This does not depend on game frames.
    _exit(0);
    return 0;
}

__attribute__((constructor)) static void Initialize(void)
{
    if (!AutomationEnabled()) return;
    const char* owner = getenv("DM_EDITOR_GAME_PARENT");
    const char* path = getenv("DM_EDITOR_GAME_FILE");
    pid_t parent = owner ? (pid_t)strtol(owner, 0, 10) : 0;
    if (!path || parent <= 1 || getppid() != parent) _exit(125);
    g_Queue = kqueue();
    if (g_Queue < 0) _exit(125);
    struct kevent event;
    EV_SET(&event, parent, EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, 0);
    if (kevent(g_Queue, &event, 1, 0, 0, 0) < 0 || getppid() != parent) _exit(125);
    pthread_t watcher;
    if (pthread_create(&watcher, 0, WatchOwner, 0) != 0) _exit(125);
    pthread_detach(watcher);
    int file = open(path, O_RDWR | O_NOFOLLOW);
    struct stat info;
    uint32_t magic = 0, ready = 1;
    if (file < 0 || fstat(file, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != getuid()
        || pread(file, &magic, sizeof(magic), 268 * 4) != sizeof(magic) || magic != 0x41555431
        || pwrite(file, &ready, sizeof(ready), 279 * 4) != sizeof(ready)) _exit(125);
    close(file);
}

static int LocalBind(int socket, const struct sockaddr* address, socklen_t length)
{
    if (AutomationEnabled() && address)
    {
        if (address->sa_family == AF_INET && length >= sizeof(struct sockaddr_in))
        {
            struct sockaddr_in local = *(const struct sockaddr_in*)address;
            if (local.sin_addr.s_addr == htonl(INADDR_ANY)) local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            return bind(socket, (const struct sockaddr*)&local, sizeof(local));
        }
        if (address->sa_family == AF_INET6 && length >= sizeof(struct sockaddr_in6))
        {
            struct sockaddr_in6 local = *(const struct sockaddr_in6*)address;
            if (IN6_IS_ADDR_UNSPECIFIED(&local.sin6_addr)) local.sin6_addr = in6addr_loopback;
            return bind(socket, (const struct sockaddr*)&local, sizeof(local));
        }
    }
    return bind(socket, address, length);
}

__attribute__((used, section("__DATA,__interpose"))) static const struct
{
    const void* replacement;
    const void* original;
} g_Bind = {(const void*)LocalBind, (const void*)bind};
