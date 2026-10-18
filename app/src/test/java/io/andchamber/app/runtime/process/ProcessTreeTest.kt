package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessTreeTest {
    @get:Rule val folder = TemporaryFolder()

    private val proot = "/data/app/x/lib/arm64/libandchamber_android_proot.so"
    private val rootfs = "/data/user/0/io.andchamber.app/files/runtime/environment/rootfs"
    private val uid = 10123

    /** Writes a fake /proc/<pid> entry. Fields mirror the real layout closely enough for the parser. */
    private fun proc(
        root: File,
        pid: Long,
        ppid: Long,
        argv: List<String>,
        uid: Int = this.uid,
        start: Long = 1000L + pid,
        comm: String = "proot",
    ) {
        val dir = root.resolve(pid.toString()).apply { mkdirs() }
        dir.resolve("status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        // Fields after ")", index 0 = state, 1 = ppid, 19 = starttime (field 22). Built by index so the
        // position of starttime is exact: 2..18 are filler (pgrp .. itrealvalue).
        val fields = listOf("S", "$ppid") + List(17) { "0" } + listOf("$start", "0", "0")
        val after = fields.joinToString(" ")
        dir.resolve("stat").writeText("$pid ($comm) $after")
        dir.resolve("cmdline").writeText(argv.joinToString("\u0000", postfix = "\u0000"))
    }

    @Test
    fun `post-order walk lists children before their parent`() {
        val procRoot = folder.newFolder("proc")
        val s = ProcessScanner(procRoot, uid, myPid = 1L)
        proc(procRoot, 10, 1, listOf(proot, "-r", rootfs))
        proc(procRoot, 11, 10, listOf("node"))
        proc(procRoot, 12, 10, listOf("sh"))
        proc(procRoot, 13, 11, listOf("npm"))

        val root = OwnedProcess(10, 1, 1010L)
        assertEquals(listOf(13L, 11L, 12L, 10L), s.treePostOrder(root).map { it.pid })
    }

    @Test
    fun `a process is owned only with our uid, exact launcher argv0 and our rootfs`() {
        val procRoot = folder.newFolder("proc-own")
        proc(procRoot, 100, 1, listOf(proot, "-0", "-r", rootfs, "/bin/node"))
        proc(procRoot, 101, 1, listOf(proot, "-r", rootfs), uid = 999) // other uid
        proc(procRoot, 102, 1, listOf("sh", "-c", rootfs)) // argv0 is not our launcher
        proc(procRoot, 103, 1, listOf(proot, "-r", "/data/other/rootfs")) // other runtime
        proc(procRoot, 104, 1, listOf("$proot-evil", "-r", rootfs)) // lookalike path

        val owned = ProcessScanner(procRoot, uid, myPid = 1L).ownedRoots(proot, rootfs)

        assertEquals(listOf(100L), owned.map { it.pid })
    }

    @Test
    fun `our own pid is never owned`() {
        val procRoot = folder.newFolder("proc-self")
        proc(procRoot, 77, 1, listOf(proot, "-r", rootfs))

        assertTrue(ProcessScanner(procRoot, uid, myPid = 77L).ownedRoots(proot, rootfs).isEmpty())
    }

    @Test
    fun `a shell with the runtime path in argv is not ours`() {
        val procRoot = folder.newFolder("proc-sh")
        proc(procRoot, 50, 1, listOf("/system/bin/sh", "-c", "cd $rootfs && ls"))

        assertTrue(ProcessScanner(procRoot, uid, myPid = 1L).ownedRoots(proot, rootfs).isEmpty())
    }

    @Test
    fun `a pid reused between the owned scan and the tree walk is not walked`() {
        val procRoot = folder.newFolder("proc-reuse")
        proc(procRoot, 300, 1, listOf(proot, "-r", rootfs), start = 1000L)
        proc(procRoot, 301, 300, listOf("node"), start = 1001L)
        val s = ProcessScanner(procRoot, uid, myPid = 1L)
        val owned = s.ownedRoots(proot, rootfs).single()

        // Between the scan and the walk the original launcher exits and pid 300 is reused by an
        // unrelated process of ours (different start time).
        proc(procRoot, 300, 1, listOf("sh", "-c", "sleep 1"), start = 9999L)

        assertTrue(s.treePostOrder(owned).isEmpty())
    }

    @Test
    fun `tree nodes owned by another uid are never collected`() {
        val procRoot = folder.newFolder("proc-uid-node")
        proc(procRoot, 400, 1, listOf(proot, "-r", rootfs))
        proc(procRoot, 401, 400, listOf("node"), uid = 999)
        val s = ProcessScanner(procRoot, uid, myPid = 1L)

        assertEquals(listOf(400L), s.treePostOrder(OwnedProcess(400, 1, 1400L)).map { it.pid })
    }

    @Test
    fun `kill is skipped when the start time changed (recycled pid)`() {
        val procRoot = folder.newFolder("proc-recycle")
        proc(procRoot, 200, 1, listOf(proot, "-r", rootfs), start = 5555L)
        val scanner = ProcessScanner(procRoot, uid, myPid = 1L)
        val recorded = OwnedProcess(200, 1, startTime = 1111L)

        val signalled = mutableListOf<Long>()
        scanner.killOwned(listOf(recorded)) { signalled += it }

        assertTrue(signalled.isEmpty())
    }

    @Test
    fun `kill signals when the start time matches`() {
        val procRoot = folder.newFolder("proc-match")
        proc(procRoot, 201, 1, listOf(proot, "-r", rootfs), start = 4242L)
        val scanner = ProcessScanner(procRoot, uid, myPid = 1L)

        val signalled = mutableListOf<Long>()
        scanner.killOwned(listOf(OwnedProcess(201, 1, startTime = 4242L))) { signalled += it }

        assertEquals(listOf(201L), signalled)
    }

    @Test
    fun `stat parsing handles a command name containing spaces and a closing paren`() {
        // Name is "weird ) name": parsing must start after the LAST ')'.
        // state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime cutime
        // cstime priority nice num_threads itrealvalue starttime  (starttime is field 22 = index 19)
        // Fields after ')' by index: 0 state, 1 ppid(42), 2..18 filler, 19 starttime(7777).
        val fields = listOf("S", "42") + List(17) { "0" } + listOf("7777")
        val stat = "300 (weird ) name) " + fields.joinToString(" ")
        assertEquals(42L to 7777L, ProcessScanner.parseStat(stat))
    }

    @Test
    fun `real uid is the first Uid field`() {
        assertEquals(10123, ProcessScanner.parseRealUid("Name:\tx\nUid:\t10123\t10123\t10123\t10123\n"))
        assertFalse(ProcessScanner.parseRealUid("Name:\tx\n") != null)
    }

    @Test
    fun `log truncation keeps recent content when file exceeds max bytes`() {
        val logFile = folder.newFile("openchamber.log")
        val line = "abcdefghijklmnopqrstuvwxyz\n"
        logFile.writeText(List(20) { "$it$line" }.joinToString(""))

        truncateLogFile(logFile, 100L)

        val remaining = logFile.readText()
        assertTrue("truncated log is too large: ${remaining.length}", remaining.length <= 50 + 30)
        assertTrue(remaining.contains("19$line") || remaining.contains("18$line"))
    }
}
