package org.thoughtcrime.securesms.conversation;

import android.content.res.Resources;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.util.Util;

final class ConversationSwipeAnimationHelper {

  private static final float PROGRESS_SCALE_FACTOR = 2.0f;

  private static final Interpolator BUBBLE_INTERPOLATOR           = new ClampingLinearInterpolator(0f, dpToPx(48));
  private static final Interpolator REPLY_ALPHA_INTERPOLATOR      = new ClampingLinearInterpolator(0.45f, 0.75f);
  private static final Interpolator REPLY_TRANSITION_INTERPOLATOR = new ClampingLinearInterpolator(0f, dpToPx(10));
  private static final Interpolator AVATAR_INTERPOLATOR           = new ClampingLinearInterpolator(0f, dpToPx(8));
  private static final Interpolator REPLY_SCALE_INTERPOLATOR      = new OverShootZoomInterpolator();

  private ConversationSwipeAnimationHelper() {
  }

  public static void update(@NonNull ConversationItem conversationItem, float progress, float sign) {
    float scaledProgress = Math.min(1f, progress * PROGRESS_SCALE_FACTOR);
    updateBodyBubbleTransition(conversationItem.bodyBubble, scaledProgress, sign);
    updateReplyIconTransition(conversationItem.reply, scaledProgress, sign);
    updateContactPhotoHolderTransition(conversationItem.contactPhotoHolder, scaledProgress, sign);
  }

  private static void updateBodyBubbleTransition(@NonNull View bodyBubble, float progress, float sign) {
    bodyBubble.setTranslationX(BUBBLE_INTERPOLATOR.getInterpolation(progress) * sign);
  }

  private static void updateReplyIconTransition(@NonNull View replyIcon, float progress, float sign) {
    if (progress > 0.05f)
      replyIcon.setAlpha(REPLY_ALPHA_INTERPOLATOR.getInterpolation(progress));
    else replyIcon.setAlpha(0f);

    replyIcon.setTranslationX(REPLY_TRANSITION_INTERPOLATOR.getInterpolation(progress) * sign);

    float scale = REPLY_SCALE_INTERPOLATOR.getInterpolation(progress);
    replyIcon.setScaleX(scale);
    replyIcon.setScaleY(scale);
  }

  private static void updateContactPhotoHolderTransition(@Nullable View contactPhotoHolder,
                                                         float progress,
                                                         float sign)
  {
    if (contactPhotoHolder == null) return;
    contactPhotoHolder.setTranslationX(AVATAR_INTERPOLATOR.getInterpolation(progress) * sign);
  }

  private static int dpToPx(int dp) {
    return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
  }

  private static final class ClampingLinearInterpolator implements Interpolator {

    private final float slope;
    private final float yIntercept;
    private final float max;
    private final float min;

    ClampingLinearInterpolator(float start, float end) {
      slope      = (end - start);
      yIntercept = start;
      max        = Math.max(start, end);
      min        = Math.min(start, end);
    }

    @Override
    public float getInterpolation(float input) {
      return Util.clamp(slope * input + yIntercept, min, max);
    }
  }

  private static final class OverShootZoomInterpolator implements Interpolator {

    @Override
    public float getInterpolation(float input) {
      if (input < 0.5) return 1f;

      float tension = 6;

      float t         = (input - 0.5f) * 2f - 1f;
      float overshoot = t * t * ((tension + 1) * t + tension) + 1.0f;

      return 1f + overshoot * 0.2f;
    }
  }

}
