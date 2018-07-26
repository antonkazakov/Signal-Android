package org.thoughtcrime.securesms.logging;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersistentLogger extends Log.Logger {

  private static final String TAG     = PersistentLogger.class.getSimpleName();

  private static final String LOG_V   = "V";
  private static final String LOG_D   = "D";
  private static final String LOG_I   = "I";
  private static final String LOG_W   = "W";
  private static final String LOG_E   = "E";
  private static final String LOG_WTF = "A";

  private static final String           FILENAME_PREFIX = "log_";
  private static final int              MAX_LOG_FILES   = 5;
  private static final int              MAX_LOG_LINES   = 500;
  private static final SimpleDateFormat DATE_FORMAT     = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz");
  private static final Pattern          NEWLINE_PATTERN = Pattern.compile("\n");

  private final Context  context;
  private final Executor executor;
  private final byte[] secret;

  private Writer writer;
  private int    lineCount;

  public PersistentLogger(Context context) {
    this.context    = context.getApplicationContext();
    this.secret     = LogSecretProvider.getOrCreateAttachmentSecret(context);
    this.executor   = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "logger");
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });

    executor.execute(this::initializeWriter);
  }

  @Override
  public void v(String tag, String message, Throwable t) {
    write(LOG_V, tag, message, t);
  }

  @Override
  public void d(String tag, String message, Throwable t) {
    write(LOG_D, tag, message, t);
  }

  @Override
  public void i(String tag, String message, Throwable t) {
    write(LOG_I, tag, message, t);
  }

  @Override
  public void w(String tag, String message, Throwable t) {
    write(LOG_W, tag, message, t);
  }

  @Override
  public void e(String tag, String message, Throwable t) {
    write(LOG_E, tag, message, t);
  }

  @Override
  public void wtf(String tag, String message, Throwable t) {
    write(LOG_WTF, tag, message, t);
  }

  @WorkerThread
  public ListenableFuture<InputStream> getLogs() {
    final SettableFuture<InputStream> future = new SettableFuture<>();

    executor.execute(() -> {
      List<InputStream> streams = new LinkedList<>();

      for (int i = findFirstOpenLogFileIndex() - 1; i >= 0; i--) {
        try {
          streams.add(openInputStream(getFileForIndex(i)));
        } catch (IOException e) {
          android.util.Log.w(TAG, "Failed to open log at index " + i + " for reading. Was likely deleted. Removing reference.");
          getFileForIndex(i).delete();
        }
      }

      future.set(new SequenceInputStream(Collections.enumeration(streams)));
    });

    return future;
  }

  @WorkerThread
  private void initializeWriter() {
    try {
      File logFile = getFileForIndex(0);

      if (logFile.exists()) {
        try {
          InputStream is = openInputStream(logFile);
          lineCount = getLineCount(is);
        } catch (IOException e) {
          android.util.Log.w(TAG, "Failed to read line count from previous log.", e);
        }
      }

      writer = createWriter(openOutputStream(logFile));

    } catch (IOException e) {
      android.util.Log.e(TAG, "Failed to initialize writer.", e);
    }
  }

  private void write(String level, String tag, String message, Throwable t) {
    executor.execute(() -> {
      try {
        if (writer == null) {
          return;
        }

        if (lineCount >= MAX_LOG_LINES) {
          Util.close(writer);
          shiftLogFiles();
          trimLogFilesOverMax();

          writer    = createWriter(openOutputStream(getFileForIndex(0)));
          lineCount = 0;
        }

        String entry = buildLogEntry(level, tag, message, t);
        writer.write(entry);
        writer.flush();
        lineCount += getLineCount(entry);

      } catch (IOException e) {
        android.util.Log.w(TAG, "Failed to write line. Deleting all logs and starting over.");
        deleteAllLogs();
        initializeWriter();
      }
    });
  }

  private void shiftLogFiles() {
    for (int i = findFirstOpenLogFileIndex(); i > 0; i--) {
      getFileForIndex(i - 1).renameTo(getFileForIndex(i));
    }
  }

  private void trimLogFilesOverMax() {
    int i = MAX_LOG_FILES;
    File file;
    while ((file = getFileForIndex(i)).exists()) {
      file.delete();
    }
  }

  private void deleteAllLogs() {
    for (int i = 0, len = findFirstOpenLogFileIndex(); i < len; i++) {
      getFileForIndex(i).delete();
    }
  }

  private int findFirstOpenLogFileIndex() {
    int i = 0;
    while (getFileForIndex(i).exists()) {
      i++;
    }
    return i;
  }

  private File getFileForIndex(int index) {
    return new File(context.getCacheDir(), FILENAME_PREFIX + index);
  }

  private InputStream openInputStream(@NonNull File file) throws IOException {
    return LogStreams.createInputStream(secret, file);
  }

  private OutputStream openOutputStream(@NonNull File file) throws IOException {
    return LogStreams.createOutputStream(secret, file, file.exists());
  }

  private Writer createWriter(@NonNull OutputStream outputStream) {
    return new BufferedWriter(new PrintWriter(outputStream));
  }

  private String buildLogEntry(String level, String tag, String message, Throwable t) {
    StringBuilder builder = new StringBuilder();

    builder.append(DATE_FORMAT.format(new Date()))
           .append(' ')
           .append(level)
           .append(' ')
           .append(tag)
           .append('\t')
           .append(message);

    if (t != null) {
      builder.append('\n');

      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      t.printStackTrace(new PrintStream(outputStream));
      builder.append(new String(outputStream.toByteArray()));
    }

    builder.append('\n');

    return builder.toString();
  }

  private int getLineCount(@NonNull String s) {
    Matcher m = NEWLINE_PATTERN.matcher(s);

    int lines = 0;
    while (m.find()) lines++;

    return lines;
  }

  private int getLineCount(@NonNull InputStream inputStream) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

    int lineCount = 0;
    while (reader.readLine() != null) {
      lineCount++;
    }

    return lineCount;
  }
}
