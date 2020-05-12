package org.thoughtcrime.securesms.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.media.DecryptableUriMediaInput;
import org.thoughtcrime.securesms.media.MediaInput;
import org.thoughtcrime.securesms.mms.AudioSlide;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.M)
public final class AudioWaveForm {

  private static final String TAG = Log.tag(AudioWaveForm.class);

  private static final int BARS            = 36;
  private static final int SAMPLES_PER_BAR =  4;

  private final AudioSlide slide;
  private final Context    context;

  public AudioWaveForm(AudioSlide slide, Context context) {
    this.slide   = slide;
    this.context = context;
  }

  private static final LruCache<Uri, AudioFileInfo> WAVE_FORM_CACHE        = new LruCache<>(200);
  private static final Executor                     AUDIO_DECODER_EXECUTOR = SignalExecutors.BOUNDED;

  @AnyThread
  public void generateWaveForm(@NonNull Consumer<AudioFileInfo> onSuccess, @NonNull Consumer<IOException> onFailure) {
    AUDIO_DECODER_EXECUTOR.execute(() -> {
      try {
        long startTime = System.currentTimeMillis();
        Uri uri = slide.getUri();
        if (uri == null) {
          Util.runOnMain(() -> onSuccess.accept(null));
          return;
        }

        AudioFileInfo cached = WAVE_FORM_CACHE.get(uri);
        if (cached != null) {
          Util.runOnMain(() -> onSuccess.accept(cached));
          return;
        }

        AudioFileInfo fileInfo = generateWaveForm(uri, BARS);
        WAVE_FORM_CACHE.put(uri, fileInfo);
        Log.d("ALAN", "Form loadtime " + (System.currentTimeMillis() - startTime));
        Util.runOnMain(() -> onSuccess.accept(fileInfo));
      } catch (IOException e) {
        Log.e(TAG, "", e);
        onFailure.accept(e);
      }
    });
  }

  @WorkerThread
  @RequiresApi(api = 23)
  private AudioFileInfo generateWaveForm(@NonNull Uri uri, int bars) throws IOException {
    try (MediaInput dataSource = DecryptableUriMediaInput.createForUri(context, uri)) {
      long[] wave = new long[bars];
      int[] waveSamples = new int[bars];

      int[] inputSamples = new int[bars * SAMPLES_PER_BAR];
      MediaCodec codec;
      ByteBuffer[] codecInputBuffers;
      ByteBuffer[] codecOutputBuffers;
      MediaExtractor extractor = dataSource.createExtractor();
      MediaFormat format = extractor.getTrackFormat(0);
      long totalDurationUs = format.getLong(MediaFormat.KEY_DURATION);

      String mime = format.getString(MediaFormat.KEY_MIME);
      //assertTrue("not an audio file", mime.startsWith("audio/"));
      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(format, null, null, 0);
      codec.start();
      codecInputBuffers = codec.getInputBuffers();
      codecOutputBuffers = codec.getOutputBuffers();
      extractor.selectTrack(0);
      // start decoding
      final long kTimeOutUs = 5000;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean sawInputEOS = false;
      boolean sawOutputEOS = false;
      int noOutputCounter = 0;

      while (!sawOutputEOS && noOutputCounter < 50) {
        noOutputCounter++;
        if (!sawInputEOS) {
          int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
          if (inputBufIndex >= 0) {
            ByteBuffer dstBuf = codecInputBuffers[inputBufIndex];
            int sampleSize = extractor.readSampleData(dstBuf, 0);
            long presentationTimeUs = 0;
            if (sampleSize < 0) {
              Log.d(TAG, "saw input EOS.");
              sawInputEOS = true;
              sampleSize = 0;
            } else {
              presentationTimeUs = extractor.getSampleTime();
            }
            codec.queueInputBuffer(
              inputBufIndex,
              0,
              sampleSize,
              presentationTimeUs,
              sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (!sawInputEOS) {
              int barIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
              inputSamples[barIndex]++;
              sawInputEOS = !extractor.advance();
              if (inputSamples[barIndex] > 0) {
                //advance to next bar
                int nextBarIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
                while (!sawInputEOS && nextBarIndex == barIndex) {
                  sawInputEOS = !extractor.advance();
                  if (!sawInputEOS) {
                    nextBarIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
                  }
                }
              }
            }
          }
        }

        int outputBufferIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);
        if (outputBufferIndex >= 0) {
          if (info.size > 0) {
            noOutputCounter = 0;
          }

          ByteBuffer buf = codecOutputBuffers[outputBufferIndex];
          int barIndex = (int) ((wave.length * info.presentationTimeUs) / totalDurationUs) - 1;
          long total = 0;
          for (int i = 0; i < info.size; i += 2 * 4) {
            short aShort = buf.getShort(i);
            total += Math.abs(aShort);
          }
          if (barIndex > 0) {
            wave[barIndex] += total;
            waveSamples[barIndex] += info.size / 2;
          }
          codec.releaseOutputBuffer(outputBufferIndex, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "saw output EOS.");
            sawOutputEOS = true;
          }
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
          codecOutputBuffers = codec.getOutputBuffers();
          Log.d(TAG, "output buffers have changed.");
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat oformat = codec.getOutputFormat();
          Log.d(TAG, "output format has changed to " + oformat);
        } else {
          Log.d(TAG, "dequeueOutputBuffer returned " + outputBufferIndex);
        }
      }
      codec.stop();
      codec.release();
      float[] floats = new float[bars];
      float max = 0;
      for (int i = 0; i < bars; i++) {
        floats[i] = wave[i] / (float) waveSamples[i];
        if (floats[i] > max) {
          max = floats[i];
        }
      }
      for (int i = 0; i < bars; i++) {
        floats[i] /= max;
      }
      return new AudioFileInfo(totalDurationUs, floats);
    }
  }

  public static class AudioFileInfo {

    private final long    durationUs;
    private final float[] waveForm;

    private AudioFileInfo(long durationUs, float[] waveForm) {
      this.durationUs = durationUs;
      this.waveForm   = waveForm;
    }

    public long getDuration(@NonNull TimeUnit timeUnit) {
      return timeUnit.convert(durationUs, TimeUnit.MICROSECONDS);
    }

    public float[] getWaveForm() {
      return waveForm;
    }
  }
}
