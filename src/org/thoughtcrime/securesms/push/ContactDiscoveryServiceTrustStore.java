package org.thoughtcrime.securesms.push;

import android.content.Context;

import org.thoughtcrime.securesms.R;
import org.whispersystems.signalservice.api.push.TrustStore;

import java.io.InputStream;

public class ContactDiscoveryServiceTrustStore implements TrustStore {

  private final Context context;

  public ContactDiscoveryServiceTrustStore(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public InputStream getKeyStoreInputStream() {
    return context.getResources().openRawResource(R.raw.ias);
  }

  @Override
  public String getKeyStorePassword() {
    return "whisper";
  }
}
