/*
 * vfork probe for the x86_64 PRoot runtime gate (see docs/testing-x86-runtime.md).
 *
 * vfork is issued with inline assembly: the child only executes SYS_exit (42) and never touches a C
 * frame that is shared with the parent. A C-level vfork child is unsafe and was seen to trap.
 * Built statically with glibc, so it runs unchanged inside a musl/Alpine guest.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

int main(void) {
    long rc;
    __asm__ volatile(
        "mov $%c1, %%eax\n\t"
        "syscall\n\t"
        "test %%rax, %%rax\n\t"
        "jnz 1f\n\t"
        "mov $42, %%edi\n\t"
        "mov $60, %%eax\n\t"
        "syscall\n\t"
        "1:\n\t"
        : "=a"(rc)
        : "i"(SYS_vfork)
        : "rcx", "r11", "memory");
    if (rc < 0) {
        printf("vfork rc=%ld errno=%ld (%s)\n", rc, -rc, strerror(-rc));
        fflush(stdout);
        return 0;
    }
    int st = 0;
    waitpid((pid_t)rc, &st, 0);
    printf("vfork child=%ld exit=%d\n", rc, WIFEXITED(st) ? WEXITSTATUS(st) : -1);
    fflush(stdout);
    return 0;
}
