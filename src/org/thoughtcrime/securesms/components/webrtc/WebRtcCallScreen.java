/*
 * Copyright (C) 2016 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms.components.webrtc;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhotoFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.webrtc.SurfaceViewRenderer;

/**
 * A UI widget that encapsulates the entire in-call screen
 * for both initiators and responders.
 *
 * @author Moxie Marlinspike
 *
 */
public class WebRtcCallScreen extends FrameLayout implements Recipient.RecipientModifiedListener {

  private static final String TAG = WebRtcCallScreen.class.getSimpleName();

  private ImageView            photo;
  private PercentFrameLayout   localRenderLayout;
  private PercentFrameLayout   remoteRenderLayout;
  private TextView             name;
  private TextView             phoneNumber;
  private TextView             label;
  private TextView             elapsedTime;
  private TextView             status;
  private FloatingActionButton endCallButton;
  private WebRtcCallControls   controls;

  private Recipient recipient;

  private WebRtcIncomingCallOverlay incomingCallOverlay;

  public WebRtcCallScreen(Context context) {
    super(context);
    initialize();
  }

  public WebRtcCallScreen(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public WebRtcCallScreen(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize();
  }

  public void setActiveCall(@NonNull Recipient personInfo, @NonNull String message, @Nullable String sas) {
    setCard(personInfo, message);
    setConnected(WebRtcCallService.localRenderer, WebRtcCallService.remoteRenderer);
    incomingCallOverlay.setActiveCall(sas);
  }

  public void setActiveCall(Recipient personInfo, String message) {
    setCard(personInfo, message);
    incomingCallOverlay.setActiveCall();
  }

  public void setIncomingCall(Recipient personInfo) {
    setCard(personInfo, getContext().getString(R.string.CallScreen_Incoming_call));
    incomingCallOverlay.setIncomingCall();
  }

  public void reset() {
    setPersonInfo(Recipient.getUnknownRecipient());
    this.status.setText("");
    this.recipient = null;
    this.controls.reset();
    this.localRenderLayout.removeAllViews();
    this.remoteRenderLayout.removeAllViews();

    incomingCallOverlay.reset();
  }

  public void setIncomingCallActionListener(WebRtcIncomingCallOverlay.IncomingCallActionListener listener) {
    incomingCallOverlay.setIncomingCallActionListener(listener);
  }

  public void setAudioMuteButtonListener(WebRtcCallControls.MuteButtonListener listener) {
    this.controls.setAudioMuteButtonListener(listener);
  }

  public void setVideoMuteButtonListener(WebRtcCallControls.MuteButtonListener listener) {
    this.controls.setVideoMuteButtonListener(listener);
  }

  public void setAudioButtonListener(WebRtcCallControls.AudioButtonListener listener) {
    this.controls.setAudioButtonListener(listener);
  }

  public void setHangupButtonListener(final HangupButtonListener listener) {
    endCallButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        listener.onClick();
      }
    });
  }

  public void notifyBluetoothChange() {
    this.controls.updateAudioButton();
  }

  public void setLocalVideoEnabled(boolean enabled) {
    if (enabled) {
      this.localRenderLayout.setHidden(false);
    } else {
      this.localRenderLayout.setHidden(true);
    }

    this.localRenderLayout.requestLayout();
  }

  public void setRemoteVideoEnabled(boolean enabled) {
    if (enabled) {
      this.remoteRenderLayout.setHidden(false);
    } else {
      this.remoteRenderLayout.setHidden(true);
    }

    this.remoteRenderLayout.requestLayout();
  }

  private void initialize() {
    LayoutInflater inflater = (LayoutInflater)getContext()
                              .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    inflater.inflate(R.layout.webrtc_call_screen, this, true);

    this.elapsedTime        = (TextView) findViewById(R.id.elapsedTime);
    this.photo              = (ImageView) findViewById(R.id.photo);
    this.localRenderLayout  = (PercentFrameLayout) findViewById(R.id.local_render_layout);
    this.remoteRenderLayout = (PercentFrameLayout) findViewById(R.id.remote_render_layout);
    this.phoneNumber        = (TextView) findViewById(R.id.phoneNumber);
    this.name               = (TextView) findViewById(R.id.name);
    this.label              = (TextView) findViewById(R.id.label);
    this.status             = (TextView) findViewById(R.id.callStateLabel);
    this.controls           = (WebRtcCallControls) findViewById(R.id.inCallControls);
    this.endCallButton      = (FloatingActionButton) findViewById(R.id.hangup_fab);
    this.incomingCallOverlay = (WebRtcIncomingCallOverlay)findViewById(R.id.callControls);

    this.localRenderLayout.setHidden(true);
    this.remoteRenderLayout.setHidden(true);
  }

  private void setConnected(SurfaceViewRenderer localRenderer,
                            SurfaceViewRenderer remoteRenderer)
  {
    localRenderLayout.setPosition(7, 7, 25, 25);
    localRenderLayout.setSquare(true);
    remoteRenderLayout.setPosition(0, 0, 100, 100);

    localRenderer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                               ViewGroup.LayoutParams.MATCH_PARENT));

    remoteRenderer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                ViewGroup.LayoutParams.MATCH_PARENT));

    localRenderer.setMirror(true);
    localRenderer.setZOrderMediaOverlay(true);

    localRenderLayout.addView(localRenderer);
    remoteRenderLayout.addView(remoteRenderer);
  }

  private void setPersonInfo(final @NonNull Recipient recipient) {
    this.recipient = recipient;
    this.recipient.addListener(this);

    final Context context = getContext();

    new AsyncTask<Void, Void, ContactPhoto>() {
      @Override
      protected ContactPhoto doInBackground(Void... params) {
        DisplayMetrics metrics       = new DisplayMetrics();
        WindowManager  windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Uri            contentUri    = ContactsContract.Contacts.lookupContact(context.getContentResolver(),
                                                                               recipient.getContactUri());
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return ContactPhotoFactory.getContactPhoto(context, contentUri, null, metrics.widthPixels);
      }

      @Override
      protected void onPostExecute(final ContactPhoto contactPhoto) {
        WebRtcCallScreen.this.photo.setImageDrawable(contactPhoto.asCallCard(context));
      }
    }.execute();

    this.name.setText(recipient.getName());
    this.phoneNumber.setText(recipient.getNumber());
  }

  private void setCard(Recipient recipient, String status) {
    setPersonInfo(recipient);
    this.status.setText(status);
  }

  @Override
  public void onModified(Recipient recipient) {
    if (recipient == this.recipient) {
      setPersonInfo(recipient);
    }
  }

  public static interface HangupButtonListener {
    public void onClick();
  }


}
