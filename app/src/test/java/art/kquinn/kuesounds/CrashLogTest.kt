package art.kquinn.kuesounds

import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Android's record of a freeze (made by hand in Android's trace layout). */
class CrashLogTest {
    @Test fun keepsTheMainThreadAndOurCode() {
        val trace = """
"Signal Catcher" daemon prio=10 tid=7 Runnable
  at x.y(Z.java:1)

"main" prio=5 tid=1 Blocked
  | group="main" sCount=1
  at java.lang.Object.wait(Native method)
  - waiting to lock <0x0a> (a art.kquinn.kuesounds.Scanner) held by thread 23
  at art.kquinn.kuesounds.Scanner.changeTracks(Scanner.kt:315)
  at art.kquinn.kuesounds.Ui.Banner(Ui.kt:570)

"DefaultDispatcher-worker-1" daemon prio=5 tid=23 Native
  at java.net.SocketInputStream.read(Native method)
"""
        val got = CrashLog.mainThread(trace)
        assertTrue(got, got.contains("Scanner.changeTracks"))
        assertTrue(got, got.contains("waiting to lock"))
        assertTrue(got, !got.contains("SocketInputStream"))
    }
}
