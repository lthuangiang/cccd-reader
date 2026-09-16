package com.altisss.cccdreader.crash

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bắt MỌI lỗi chưa được try/catch ở đâu đó trong app (kể cả trên thread khác, kể cả trong
 * thư viện bên thứ 3 như JMRTD/SCUBA) - thay vì để app tự crash im lặng/văng về home,
 * ghi full stack trace ra file rồi mở CrashActivity hiển thị cho người dùng xem + copy.
 *
 * Cài đặt 1 lần duy nhất trong CccdReaderApp.onCreate().
 */
class GlobalCrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val fullTrace = buildString {
                append("Thread: ${thread.name}\n")
                append("Time: ${System.currentTimeMillis()}\n\n")
                append(sw.toString())
            }

            val file = File(appContext.filesDir, CRASH_FILE_NAME)
            file.writeText(fullTrace)

            val intent = Intent(appContext, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Throwable) {
            // Nếu ngay cả việc ghi crash log cũng lỗi, đành fallback về handler mặc định của hệ thống
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"

        fun install(appContext: Context) {
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(appContext))
        }

        fun readLastCrash(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            return if (file.exists()) file.readText() else null
        }
    }
}