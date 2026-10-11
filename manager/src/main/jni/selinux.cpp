#include <fcntl.h>
#include <cstring>
#include <unistd.h>
#include <dlfcn.h>
#include <cerrno>
#include <syscall.h>
#include <cstdlib>
#include <cstdio>
#include "selinux.h"

namespace se {

    static int __getcon(char **context) {
        int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            return -1;
        }

        size_t size = sysconf(_SC_PAGE_SIZE);
        char *buf = (char *) malloc(size);
        if (!buf) {
            close(fd);
            return -1;
        }

        ssize_t ret;
        do {
            ret = read(fd, buf, size - 1);
        } while (ret < 0 && errno == EINTR);

        close(fd);

        if (ret < 0) {
            free(buf);
            return -1;
        }

        if (ret == 0) {
            *context = nullptr;
            free(buf);
            return 0;
        }

        buf[ret] = '\0';
        *context = strdup(buf);
        free(buf);

        if (!(*context)) {
            return -1;
        }

        return 0;
    }

    static int __setcon(const char *ctx) {
        int fd = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
        if (fd < 0)
            return fd;
        size_t len = strlen(ctx) + 1;
        ssize_t rc = write(fd, ctx, len);
        close(fd);
        return rc != len;
    }

    static int __setfilecon(const char *path, const char *ctx) {
        int rc = syscall(__NR_setxattr, path, "security.selinux"/*XATTR_NAME_SELINUX*/, ctx,
                         strlen(ctx) + 1, 0);
        if (rc) {
            errno = -rc;
            return -1;
        }
        return 0;
    }

    static int __selinux_check_access(const char *scon, const char *tcon,
                                      const char *tclass, const char *perm, void *auditdata) {
        fprintf(stderr, "DEBUG: __selinux_check_access stub called, always returning 0\n");
        return 0;
    }

    static void __freecon(char *con) {
        free(con);
    }

    getcon_t *getcon = __getcon;
    setcon_t *setcon = __setcon;
    setfilecon_t *setfilecon = __setfilecon;
    selinux_check_access_t *selinux_check_access = __selinux_check_access;
    freecon_t *freecon = __freecon;

    void init() {
        if (access("/system/lib/libselinux.so", F_OK) != 0 && access("/system/lib64/libselinux.so", F_OK) != 0)
            return;

        void *handle = dlopen("libselinux.so", RTLD_LAZY | RTLD_LOCAL);
        if (handle == nullptr)
            return;

        getcon = (getcon_t *) dlsym(handle, "getcon");
        setcon = (setcon_t *) dlsym(handle, "setcon");
        setfilecon = (setfilecon_t *) dlsym(handle, "setfilecon");
        selinux_check_access = (selinux_check_access_t *) dlsym(handle, "selinux_check_access");
        freecon = (freecon_t *) (dlsym(handle, "freecon"));
    }
}
