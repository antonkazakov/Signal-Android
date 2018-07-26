package org.thoughtcrime.securesms.logging;

import android.support.annotation.NonNull;

import org.thoughtcrime.securesms.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class LogStreams {

  static OutputStream createOutputStream(@NonNull byte[] secret, @NonNull File file, boolean append) throws IOException {
    byte[] random = new byte[32];

    if (append) {
      FileInputStream inputStream = new FileInputStream(file);
      Util.readFully(inputStream, random);
    } else {
      new SecureRandom().nextBytes(random);
    }

    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));

      FileOutputStream fileOutputStream = new FileOutputStream(file, append);
      byte[]           iv               = new byte[16];
      byte[]           key              = mac.doFinal(random);

      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

      if (append) {
        InputStream is     = createInputStream(secret, file);
        byte[]      buffer = new byte[4096];
        int         read   = 0;

        while ((read = is.read(buffer)) != -1) {
          cipher.update(buffer, 0, read);
        }

        is.close();
      } else {
        fileOutputStream.write(random);
      }

      return new CipherOutputStream(fileOutputStream, cipher);
    } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidAlgorithmParameterException | NoSuchPaddingException e) {
      throw new AssertionError(e);
    }
  }

  static InputStream createInputStream(@NonNull byte[] secret, @NonNull File file) throws IOException {
    FileInputStream inputStream = new FileInputStream(file);
    byte[]          random      = new byte[32];

    Util.readFully(inputStream, random);

    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));

      byte[] iv     = new byte[16];
      byte[] key    = mac.doFinal(random);
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

      return new CipherInputStream(inputStream, cipher);
    } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidAlgorithmParameterException | NoSuchPaddingException e) {
      throw new AssertionError(e);
    }
  }
}
