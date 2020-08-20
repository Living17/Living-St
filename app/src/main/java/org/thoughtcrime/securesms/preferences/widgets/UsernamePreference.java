package org.thoughtcrime.securesms.preferences.widgets;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import org.thoughtcrime.securesms.R;

public class UsernamePreference extends Preference {
  public UsernamePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  public UsernamePreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public UsernamePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public UsernamePreference(Context context) {
    super(context);
    initialize();
  }

  private void initialize() {
    setLayoutResource(R.layout.preference_username);
  }

}
