// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.windows;

import com.google.devtools.build.lib.shell.Subprocess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Windows subprocess backed by a native object.
 */
public class WindowsSubprocess implements Subprocess {
  private enum Stream { OUT, ERR };

  /**
   * Output stream for writing to the stdin of a Windows process.
   */
  private class ProcessOutputStream extends OutputStream {
    private ProcessOutputStream() {
    }

    @Override
    public void write(int b) throws IOException {
      byte[] buf = new byte[]{b >= 128 ? ((byte) (b - 256)) : ((byte) b)};
      write(buf, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      writeStream(b, off, len);
    }
  }

  /**
   * Input stream for reading the stdout or stderr of a Windows process.
   */
  private class ProcessInputStream extends InputStream {
    private final Stream stream;

    ProcessInputStream(Stream stream) {
      this.stream = stream;
    }

    @Override
    public int read() throws IOException {
      byte[] buf = new byte[1];
      if (read(buf, 0, 1) != 1) {
        return -1;
      } else {
        return buf[0] < 0 ? 256 + buf[0] : buf[0];
      }
    }

    public int read(byte b[], int off, int len) throws IOException {
      return readStream(stream, b, off, len);
    }
  }

  private static AtomicInteger THREAD_SEQUENCE_NUMBER = new AtomicInteger(1);
  private static final ExecutorService WAITER_POOL = Executors.newCachedThreadPool(
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
          Thread thread = new Thread(null, runnable,
              "Windows-Process-Waiter-Thread-" + THREAD_SEQUENCE_NUMBER.getAndIncrement(),
              16 * 1024);
          thread.setDaemon(true);
          return thread;
        }
      });

  private long nativeProcess;
  private final OutputStream outputStream;
  private final InputStream inputStream;
  private final InputStream errorStream;
  private final CountDownLatch waitLatch;

  WindowsSubprocess(long nativeProcess, boolean stdoutRedirected, boolean stderrRedirected) {
    this.nativeProcess = nativeProcess;
    inputStream = stdoutRedirected ? null : new ProcessInputStream(Stream.OUT);
    errorStream = stderrRedirected ? null : new ProcessInputStream(Stream.ERR);
    outputStream = new ProcessOutputStream();
    waitLatch = new CountDownLatch(1);
    // Every Windows process we start consumes a thread here. This is suboptimal, but seems to be
    // the sanest way to reconcile WaitForMultipleObjects() and Java-style interruption.
    WAITER_POOL.submit(new Runnable() {
        @Override public void run() {
          waiterThreadFunc();
        }
    });
  }

  private void waiterThreadFunc() {
    if (!WindowsProcesses.nativeWaitFor(nativeProcess)) {
      // There isn't a lot we can do -- the process is still alive but WaitForMultipleObjects()
      // failed for some odd reason. We'll pretend it terminated and log a message to jvm.out .
      System.err.println("Waiting for process "
          + WindowsProcesses.nativeGetProcessPid(nativeProcess) + " failed");
    }

    waitLatch.countDown();
  }

  @Override
  public synchronized void finalize() {
    if (nativeProcess != -1) {
      WindowsProcesses.nativeDelete(nativeProcess);
      nativeProcess = -1;
    }
  }

  @Override
  public synchronized boolean destroy() {
    checkLiveness();

    if (!WindowsProcesses.nativeTerminate(nativeProcess)) {
      return false;
    }

    return true;
  }

  @Override
  public synchronized int exitValue() {
    checkLiveness();

    int result = WindowsProcesses.nativeGetExitCode(nativeProcess);
    String error = WindowsProcesses.nativeGetLastError(nativeProcess);
    if (!error.isEmpty()) {
      throw new IllegalStateException(error);
    }

    return result;
  }

  @Override
  public boolean finished() {
    return waitLatch.getCount() == 0;
  }

  @Override
  public void waitFor() throws InterruptedException {
    waitLatch.await();
  }

  @Override
  public OutputStream getOutputStream() {
    return outputStream;
  }

  @Override
  public InputStream getInputStream() {
    return inputStream;
  }

  @Override
  public InputStream getErrorStream() {
    return errorStream;
  }

  private synchronized int readStream(Stream stream, byte b[], int off, int len)
      throws IOException {
    checkLiveness();

    int result = -1;
    switch (stream) {
      case OUT:
        result = WindowsProcesses.nativeReadStdout(nativeProcess, b, off, len);
        break;
      case ERR:
        result = WindowsProcesses.nativeReadStderr(nativeProcess, b, off, len);
        break;
    }

    if (result == -1) {
      throw new IOException(WindowsProcesses.nativeGetLastError(nativeProcess));
    }

    return result;
  }

  private synchronized void writeStream(byte[] b, int off, int len) throws IOException {
    checkLiveness();

    int remaining = len;
    int currentOffset = off;
    while (remaining != 0) {
      int written = WindowsProcesses.nativeWriteStdin(
          nativeProcess, b, currentOffset, remaining);
      // I think the Windows API never returns 0 in dwNumberOfBytesWritten
      // Verify.verify(written != 0);
      if (written == -1) {
        throw new IOException(WindowsProcesses.nativeGetLastError(nativeProcess));
      }

      remaining -= written;
      currentOffset += written;
    }
  }

  private void checkLiveness() {
    if (nativeProcess == -1) {
      throw new IllegalStateException();
    }
  }
}
