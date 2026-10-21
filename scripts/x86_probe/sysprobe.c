/*
 * Syscall probe for the x86_64 PRoot runtime gate (see docs/testing-x86-runtime.md).
 *
 * Built statically with glibc: no interpreter, so it runs unchanged inside a musl/Alpine guest.
 * It prints one line per call. glibc fork() is implemented as clone(SIGCHLD) and never issues
 * SYS_fork (57), so this file proves guest fork/clone only. The raw SYS_fork register check is a
 * separate probe (fork_regs, built from the verified PRoot 5.1.107.98 archive).
 *
 * The vfork call is deliberately NOT here: C code cannot safely run the vfork child (it shares the
 * parent's stack). vfork is covered by vforkasm.c.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void report(const char *name, long rc) {
    printf("%-10s rc=%ld errno=%d (%s)\n", name, rc, rc < 0 ? errno : 0, rc < 0 ? strerror(errno) : "ok");
    fflush(stdout);
}

int main(void) {
    printf("uid=%d pid=%d\n", (int)getuid(), (int)getpid());
    fflush(stdout);
    report("getppid", (long)getppid());

    pid_t p = fork();
    if (p == 0) {
        _exit(7);
    }
    if (p < 0) {
        report("fork", -1);
    } else {
        int st = 0;
        waitpid(p, &st, 0);
        printf("fork       child=%d exit=%d\n", p, WEXITSTATUS(st));
        fflush(stdout);
    }

    long c = syscall(SYS_clone, SIGCHLD, 0, 0, 0, 0);
    if (c == 0) {
        _exit(9);
    }
    if (c < 0) {
        report("clone", c);
    } else {
        int st = 0;
        waitpid((pid_t)c, &st, 0);
        printf("clone      child=%ld exit=%d\n", c, WEXITSTATUS(st));
        fflush(stdout);
    }
    return 0;
}
