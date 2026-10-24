/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.common.util.Util.contains;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.abs;
import static java.lang.Math.max;

import android.content.Context;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Pair;
import android.util.SparseArray;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.LongArrayQueue;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A basic {@link VideoCompositor} implementation that takes in frames from input sources' streams
 * and combines them into one output stream.
 *
 * <p>The first {@linkplain #registerInputSource registered source} will be the primary stream,
 * which is used to determine the output textures' timestamps and dimensions.
 *
 * <p>The input source must be able to have at least two {@linkplain
 * VideoCompositor#queueInputTexture queued textures} in its output buffer.
 *
 * <p>When composited, textures are overlaid over one another in the reverse order of their
 * registration order, so that the first registered source is on the very top. The way the textures
 * are overlaid can be customized using the {@link OverlaySettings} output by {@link
 * VideoCompositorSettings}.
 *
 * <p>Only SDR input with the same {@link ColorInfo} are supported.
 */
@UnstableApi
public final class DefaultVideoCompositor implements VideoCompositor {
  // TODO: b/262694346 -  Flesh out this implementation by doing the following:
  //  * Use a lock to synchronize FrameInfos more narrowly, to reduce blocking.
  //  * Add support for mixing SDR streams with different ColorInfo.
  //  * Add support for HDR input.
  private static final String TAG = "DefaultVideoCompositor";

  private final VideoCompositor.Listener listener;
  private final GlTextureProducer.Listener textureOutputListener;
  private final GlObjectsProvider glObjectsProvider;
  private final DefaultCompositorGlProgram compositorGlProgram;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;

  @GuardedBy("this")
  private final SparseArray<InputSource> inputSources;

  @GuardedBy("this")
  private boolean allInputsEnded; // Whether all inputSources have signaled end of input.

  private final TexturePool outputTexturePool;
  private final LongArrayQueue outputTextureTimestamps; // Synchronized with outputTexturePool.
  private final LongArrayQueue syncObjects; // Synchronized with outputTexturePool.

  private VideoCompositorSettings videoCompositorSettings;

  @GuardedBy("this")
  private boolean inputTextureQueued;

  private @MonotonicNonNull ColorInfo configuredColorInfo;

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private int primaryInputIndex;

  /**
   * Creates an instance.
   *
   * <p>It's the caller's responsibility to {@linkplain GlObjectsProvider#release(EGLDisplay)
   * release} the {@link GlObjectsProvider} on the {@link ExecutorService}'s thread, and to
   * {@linkplain ExecutorService#shutdown shut down} the {@link ExecutorService}.
   */
  public DefaultVideoCompositor(
      Context context,
      GlObjectsProvider glObjectsProvider,
      ExecutorService executorService,
      VideoCompositor.Listener listener,
      GlTextureProducer.Listener textureOutputListener,
      @IntRange(from = 1) int textureOutputCapacity) {
    this.listener = listener;
    this.textureOutputListener = textureOutputListener;
    this.glObjectsProvider = glObjectsProvider;
    this.compositorGlProgram = new DefaultCompositorGlProgram(context);
    primaryInputIndex = C.INDEX_UNSET;

    inputSources = new SparseArray<>();
    outputTexturePool =
        new TexturePool(/* useHighPrecisionColorComponents= */ false, textureOutputCapacity);
    outputTextureTimestamps = new LongArrayQueue(textureOutputCapacity);
    syncObjects = new LongArrayQueue(textureOutputCapacity);
    videoCompositorSettings = VideoCompositorSettings.DEFAULT;

    videoFrameProcessingTaskExecutor =
        new VideoFrameProcessingTaskExecutor(
            executorService, /* shouldShutdownExecutorService= */ false, listener::onError);
    videoFrameProcessingTaskExecutor.submit(this::setupGlObjects);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The first registered input will be designated as the primary input.
   */
  @Override
  public synchronized void registerInputSource(int inputIndex) {
    checkState(!contains(inputSources, inputIndex));
    inputSources.put(inputIndex, new InputSource());
    if (primaryInputIndex == C.INDEX_UNSET) {
      primaryInputIndex = inputIndex;
    }
  }

  @Override
  public synchronized void setVideoCompositorSettings(VideoCompositorSettings videoCompositorSettings) {
    if (inputTextureQueued
        && (this.videoCompositorSettings instanceof VideoCompositorFramePolicy
            || videoCompositorSettings instanceof VideoCompositorFramePolicy)
        && this.videoCompositorSettings != videoCompositorSettings) {
      throw new IllegalStateException("VIDEO compositor policy must be installed before queuing input");
    }
    this.videoCompositorSettings = videoCompositorSettings;
  }

  @Override
  public synchronized void signalEndOfInputSource(int inputIndex) {
    checkState(contains(inputSources, inputIndex));
    checkState(primaryInputIndex != C.INDEX_UNSET);
    inputSources.get(inputIndex).isInputEnded = true;
    boolean allInputsEnded = true;
    for (int i = 0; i < inputSources.size(); i++) {
      if (!inputSources.valueAt(i).isInputEnded) {
        allInputsEnded = false;
        break;
      }
    }

    this.allInputsEnded = allInputsEnded;
    if (inputSources.get(primaryInputIndex).frameInfos.isEmpty()) {
      if (inputIndex == primaryInputIndex) {
        releaseExcessFramesInAllSecondaryStreams();
      }
      if (allInputsEnded) {
        listener.onEnded();
        return;
      }
    }
    if (inputIndex != primaryInputIndex
        && (inputSources.get(inputIndex).frameInfos.size() == 1
            || (videoCompositorSettings instanceof VideoCompositorFramePolicy
                && inputSources.get(inputIndex).frameInfos.isEmpty()))) {
      // When a secondary stream ends input, composite if there was only one pending frame in the
      // stream.
      videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
    }
  }

  @Override
  public synchronized void queueInputTexture(
      int inputIndex,
      GlTextureProducer textureProducer,
      GlTextureInfo inputTexture,
      ColorInfo colorInfo,
      long presentationTimeUs) {
    checkState(contains(inputSources, inputIndex));

    InputSource inputSource = inputSources.get(inputIndex);
    checkState(!inputSource.isInputEnded);
    checkState(!ColorInfo.isTransferHdr(colorInfo), "HDR input is not supported.");
    if (configuredColorInfo == null) {
      configuredColorInfo = colorInfo;
    }
    checkState(
        configuredColorInfo.equals(colorInfo), "Mixing different ColorInfos is not supported.");

    FrameInfo frameInfo =
        new FrameInfo(
            inputIndex,
            textureProducer,
            new TimedGlTextureInfo(inputTexture, presentationTimeUs),
            videoCompositorSettings instanceof VideoCompositorFramePolicy
                ? VideoCompositorSettings.DEFAULT.getOverlaySettings(inputIndex, presentationTimeUs)
                : videoCompositorSettings.getOverlaySettings(inputIndex, presentationTimeUs));
    inputSource.frameInfos.add(frameInfo);
    inputTextureQueued = true;

    if (inputIndex == primaryInputIndex) {
      releaseExcessFramesInAllSecondaryStreams();
    } else {
      releaseExcessFramesInSecondaryStream(inputSource);
    }

    videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
  }

  @Override
  public synchronized void release() {
    try {
      videoFrameProcessingTaskExecutor.release(/* releaseTask= */ this::releaseGlObjects);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void releaseOutputTexture(long presentationTimeUs) {
    videoFrameProcessingTaskExecutor.submit(() -> releaseOutputTextureInternal(presentationTimeUs));
  }

  private synchronized void releaseExcessFramesInAllSecondaryStreams() {
    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.keyAt(i) == primaryInputIndex) {
        continue;
      }
      releaseExcessFramesInSecondaryStream(inputSources.valueAt(i));
    }
  }

  /**
   * Release unneeded frames from the {@link InputSource} secondary stream.
   *
   * <p>After this method returns, there should be exactly zero or one frames left with a timestamp
   * less than the primary stream's next timestamp that were present when the method execution
   * began.
   */
  private synchronized void releaseExcessFramesInSecondaryStream(InputSource secondaryInputSource) {
    InputSource primaryInputSource = inputSources.get(primaryInputIndex);
    // If the primary stream output is ended, all secondary frames can be released.
    if (primaryInputSource.frameInfos.isEmpty() && primaryInputSource.isInputEnded) {
      releaseFrames(
          secondaryInputSource,
          /* numberOfFramesToRelease= */ secondaryInputSource.frameInfos.size());
      return;
    }

    // Release frames until the secondary stream has 0-2 frames with presentationTimeUs before or at
    // nextTimestampToComposite.
    @Nullable FrameInfo nextPrimaryFrame = primaryInputSource.frameInfos.peek();
    long nextTimestampToComposite =
        nextPrimaryFrame != null
            ? nextPrimaryFrame.timedGlTextureInfo.presentationTimeUs
            : C.TIME_UNSET;

    int numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp =
        Iterables.size(
            Iterables.filter(
                secondaryInputSource.frameInfos,
                frame -> frame.timedGlTextureInfo.presentationTimeUs <= nextTimestampToComposite));
    releaseFrames(
        secondaryInputSource,
        /* numberOfFramesToRelease= */ max(
            numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp - 1, 0));
  }

  private synchronized void releaseFrames(InputSource inputSource, int numberOfFramesToRelease) {
    for (int i = 0; i < numberOfFramesToRelease; i++) {
      FrameInfo frameInfoToRelease = inputSource.frameInfos.remove();
      checkNotNull(frameInfoToRelease.textureProducer)
          .releaseOutputTexture(frameInfoToRelease.timedGlTextureInfo.presentationTimeUs);
    }
  }

  // Below methods must be called on the GL thread.
  private void setupGlObjects() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    placeholderEglSurface =
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  private synchronized void maybeComposite()
      throws VideoFrameProcessingException, GlUtil.GlException {
    @Nullable VideoCompositorFramePolicy framePolicy =
        videoCompositorSettings instanceof VideoCompositorFramePolicy
            ? (VideoCompositorFramePolicy) videoCompositorSettings
            : null;
    ImmutableList<FrameInfo> framesToComposite =
        framePolicy == null ? getFramesToComposite() : getFramesToCompositeWithPolicy(framePolicy);
    if (framesToComposite.isEmpty()) {
      return;
    }

    FrameInfo primaryInputFrame = framesToComposite.get(primaryInputIndex);

    ImmutableList.Builder<Size> inputSizes = new ImmutableList.Builder<>();
    for (int i = 0; i < framesToComposite.size(); i++) {
      GlTextureInfo texture = framesToComposite.get(i).timedGlTextureInfo.glTextureInfo;
      inputSizes.add(new Size(texture.width, texture.height));
    }
    Size outputSize =
        (framePolicy == null ? videoCompositorSettings : framePolicy)
            .getOutputSize(inputSizes.build());
    // Validate/snapshot all opted-in callbacks BEFORE leasing an output texture.
    // Keep primary, size-list and source-frame order separate from GL paint order.
    @Nullable ImmutableList<GlCompositionFrame> policyFrames =
        framePolicy == null
            ? null
            : buildPolicyFrames(framePolicy, framesToComposite,
                primaryInputFrame.timedGlTextureInfo.presentationTimeUs);
    outputTexturePool.ensureConfigured(
        glObjectsProvider, outputSize.getWidth(), outputSize.getHeight());

    GlTextureInfo outputTexture = outputTexturePool.useTexture();
    long outputPresentationTimestampUs = primaryInputFrame.timedGlTextureInfo.presentationTimeUs;
    outputTextureTimestamps.add(outputPresentationTimestampUs);

    if (policyFrames != null) {
      compositorGlProgram.drawFrame(policyFrames, outputTexture);
    } else {
      ImmutableList.Builder<GlCompositionFrame> glProgramInput = new ImmutableList.Builder<>();
      for (int i = 0; i < framesToComposite.size(); i++) {
        glProgramInput.add(
            new GlCompositionFrame(
                framesToComposite.get(i).timedGlTextureInfo.glTextureInfo,
                framesToComposite.get(i).overlaySettings));
      }
      compositorGlProgram.drawFrame(glProgramInput.build(), outputTexture);
    }

    long syncObject = GlUtil.createGlSyncFence();
    syncObjects.add(syncObject);
    textureOutputListener.onTextureRendered(
        /* textureProducer= */ this, outputTexture, outputPresentationTimestampUs, syncObject);

    InputSource primaryInputSource = inputSources.get(primaryInputIndex);
    releaseFrames(primaryInputSource, /* numberOfFramesToRelease= */ 1);
    releaseExcessFramesInAllSecondaryStreams();

    if (allInputsEnded && primaryInputSource.frameInfos.isEmpty()) {
      listener.onEnded();
    }
  }

  /**
   * Checks whether {@code inputSources} is able to composite, and if so, returns a list of {@link
   * FrameInfo}s that should be composited next.
   *
   * <p>The first input frame info in the list is from the the primary source. An empty list is
   * returned if {@code inputSources} cannot composite now.
   */
  private synchronized ImmutableList<FrameInfo> getFramesToComposite() {
    if (outputTexturePool.freeTextureCount() == 0) {
      return ImmutableList.of();
    }
    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.valueAt(i).frameInfos.isEmpty()) {
        return ImmutableList.of();
      }
    }
    ImmutableList.Builder<FrameInfo> framesToComposite = new ImmutableList.Builder<>();
    FrameInfo primaryFrameToComposite = inputSources.get(primaryInputIndex).frameInfos.element();
    framesToComposite.add(primaryFrameToComposite);

    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.keyAt(i) == primaryInputIndex) {
        continue;
      }
      // Select the secondary streams' frame that would be composited next. The frame selected is
      // the closest-timestamp frame from the primary stream's frame, if all secondary streams have:
      //   1. One or more frames, and the secondary stream has ended, or
      //   2. Two or more frames, and at least one frame has timestamp greater than the target
      //      timestamp.
      // The smaller timestamp is taken if two timestamps have the same distance from the primary.
      InputSource secondaryInputSource = inputSources.valueAt(i);
      if (secondaryInputSource.frameInfos.size() == 1 && !secondaryInputSource.isInputEnded) {
        return ImmutableList.of();
      }

      long minTimeDiffFromPrimaryUs = Long.MAX_VALUE;
      @Nullable FrameInfo secondaryFrameToComposite = null;
      Iterator<FrameInfo> frameInfosIterator = secondaryInputSource.frameInfos.iterator();
      while (frameInfosIterator.hasNext()) {
        FrameInfo candidateFrame = frameInfosIterator.next();
        long candidateTimestampUs = candidateFrame.timedGlTextureInfo.presentationTimeUs;
        long candidateAbsDistance =
            abs(
                candidateTimestampUs
                    - primaryFrameToComposite.timedGlTextureInfo.presentationTimeUs);

        if (candidateAbsDistance < minTimeDiffFromPrimaryUs) {
          minTimeDiffFromPrimaryUs = candidateAbsDistance;
          secondaryFrameToComposite = candidateFrame;
        }

        if (candidateTimestampUs > primaryFrameToComposite.timedGlTextureInfo.presentationTimeUs
            || (!frameInfosIterator.hasNext() && secondaryInputSource.isInputEnded)) {
          framesToComposite.add(checkNotNull(secondaryFrameToComposite));
          break;
        }
      }
    }
    ImmutableList<FrameInfo> framesToCompositeList = framesToComposite.build();
    if (framesToCompositeList.size() != inputSources.size()) {
      return ImmutableList.of();
    }
    return framesToCompositeList;
  }

  /** Original source selection/release semantics, with opt-in contiguous source-phase admission. */
  private synchronized ImmutableList<FrameInfo> getFramesToCompositeWithPolicy(
      VideoCompositorFramePolicy policy) throws VideoFrameProcessingException {
    if (outputTexturePool.freeTextureCount() == 0) {
      return ImmutableList.of();
    }
    InputSource primaryInputSource = inputSources.get(primaryInputIndex);
    if (primaryInputSource.frameInfos.isEmpty()) {
      return ImmutableList.of();
    }
    boolean waitingForInput = false;
    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.valueAt(i).frameInfos.isEmpty()) {
        if (inputSources.keyAt(i) != primaryInputIndex && inputSources.valueAt(i).isInputEnded) {
          throw new VideoFrameProcessingException("VIDEO overlay input ended without a decoded frame");
        }
        waitingForInput = true;
      }
    }
    if (waitingForInput) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<FrameInfo> framesToComposite = new ImmutableList.Builder<>();
    FrameInfo primaryFrameToComposite = primaryInputSource.frameInfos.element();
    framesToComposite.add(primaryFrameToComposite);
    long outputTimeUs = primaryFrameToComposite.timedGlTextureInfo.presentationTimeUs;
    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.keyAt(i) == primaryInputIndex) {
        continue;
      }
      InputSource secondaryInputSource = inputSources.valueAt(i);
      if (secondaryInputSource.frameInfos.size() == 1 && !secondaryInputSource.isInputEnded) {
        return ImmutableList.of();
      }
      long minTimeDiffFromPrimaryUs = Long.MAX_VALUE;
      @Nullable FrameInfo secondaryFrameToComposite = null;
      Iterator<FrameInfo> frameInfosIterator = secondaryInputSource.frameInfos.iterator();
      while (frameInfosIterator.hasNext()) {
        FrameInfo candidateFrame = frameInfosIterator.next();
        long candidateTimestampUs = candidateFrame.timedGlTextureInfo.presentationTimeUs;
        long candidateAbsDistance = abs(candidateTimestampUs - outputTimeUs);
        if (policy.isInputFrameEligibleForOutput(
                candidateFrame.inputIndex, candidateTimestampUs, outputTimeUs)
            && candidateAbsDistance < minTimeDiffFromPrimaryUs) {
          minTimeDiffFromPrimaryUs = candidateAbsDistance;
          secondaryFrameToComposite = candidateFrame;
        }
        if (candidateTimestampUs > outputTimeUs
            || (!frameInfosIterator.hasNext() && secondaryInputSource.isInputEnded)) {
          if (secondaryFrameToComposite == null) {
            // Waiting for stream end here can deadlock its bounded gap-texture queue.
            throw new VideoFrameProcessingException(
                "VIDEO overlay has no decoded source frame in the active output interval");
          }
          framesToComposite.add(secondaryFrameToComposite);
          break;
        }
      }
    }
    ImmutableList<FrameInfo> result = framesToComposite.build();
    return result.size() == inputSources.size() ? result : ImmutableList.of();
  }

  private static ImmutableList<GlCompositionFrame> buildPolicyFrames(
      VideoCompositorFramePolicy policy, ImmutableList<FrameInfo> selectedFrames, long outputTimeUs)
      throws VideoFrameProcessingException {
    ImmutableList.Builder<Integer> inputIds = new ImmutableList.Builder<>();
    SparseArray<FrameInfo> framesByInputId = new SparseArray<>();
    for (FrameInfo frame : selectedFrames) {
      if (framesByInputId.get(frame.inputIndex) != null) {
        throw new VideoFrameProcessingException("Duplicate VIDEO compositor input ID");
      }
      inputIds.add(frame.inputIndex);
      framesByInputId.put(frame.inputIndex, frame);
    }
    List<Integer> requestedOrder = policy.getInputIdsInTopToBottomOrder(inputIds.build());
    if (requestedOrder == null || requestedOrder.size() != selectedFrames.size()) {
      throw new VideoFrameProcessingException("Invalid VIDEO compositor frame order");
    }
    // Snapshot settings as well as order before a texture is leased. The input-size
    // callback has already received original primary-first dimensions for this frame.
    ImmutableList.Builder<GlCompositionFrame> result = new ImmutableList.Builder<>();
    for (Integer inputId : requestedOrder) {
      @Nullable FrameInfo frame = inputId == null ? null : framesByInputId.get(inputId);
      if (frame == null) {
        throw new VideoFrameProcessingException("Unknown/duplicate VIDEO compositor input ID");
      }
      OverlaySettings settings = policy.getOverlaySettingsForOutput(
          inputId, frame.timedGlTextureInfo.presentationTimeUs, outputTimeUs);
      if (settings == null) {
        throw new VideoFrameProcessingException("VIDEO compositor settings must not be null");
      }
      result.add(new GlCompositionFrame(
          frame.timedGlTextureInfo.glTextureInfo, new PolicyOverlaySettings(settings)));
      framesByInputId.remove(inputId);
    }
    return result.build();
  }

  /** Immutable policy-only snapshot; all consumer getters run before output texture leasing. */
  private static final class PolicyOverlaySettings implements OverlaySettings {
    private final Pair<Float, Float> backgroundAnchor;
    private final Pair<Float, Float> overlayAnchor;
    private final Pair<Float, Float> scale;
    private final float alpha;
    private final float rotation;
    private final float hdrLuminance;

    private PolicyOverlaySettings(OverlaySettings settings) throws VideoFrameProcessingException {
      Pair<Float, Float> background = settings.getBackgroundFrameAnchor();
      Pair<Float, Float> overlay = settings.getOverlayFrameAnchor();
      Pair<Float, Float> scaling = settings.getScale();
      alpha = settings.getAlphaScale();
      rotation = settings.getRotationDegrees();
      hdrLuminance = settings.getHdrLuminanceMultiplier();
      if (!finitePair(background) || !finitePair(overlay) || !finitePair(scaling)
          || !Float.isFinite(alpha) || alpha < 0 || alpha > 1
          || !Float.isFinite(rotation) || !Float.isFinite(hdrLuminance) || hdrLuminance < 0) {
        throw new VideoFrameProcessingException("Invalid VIDEO compositor settings");
      }
      // Offscreen anchors remain valid; StaticOverlaySettings.Builder would clamp/reject them.
      backgroundAnchor = Pair.create(background.first, background.second);
      overlayAnchor = Pair.create(overlay.first, overlay.second);
      scale = Pair.create(scaling.first, scaling.second);
    }

    private static boolean finitePair(@Nullable Pair<Float, Float> pair) {
      return pair != null && pair.first != null && pair.second != null
          && Float.isFinite(pair.first) && Float.isFinite(pair.second);
    }

    @Override public Pair<Float, Float> getBackgroundFrameAnchor() { return backgroundAnchor; }
    @Override public Pair<Float, Float> getOverlayFrameAnchor() { return overlayAnchor; }
    @Override public Pair<Float, Float> getScale() { return scale; }
    @Override public float getAlphaScale() { return alpha; }
    @Override public float getRotationDegrees() { return rotation; }
    @Override public float getHdrLuminanceMultiplier() { return hdrLuminance; }
  }

  private synchronized void releaseOutputTextureInternal(long presentationTimeUs)
      throws VideoFrameProcessingException, GlUtil.GlException {
    while (outputTexturePool.freeTextureCount() < outputTexturePool.capacity()
        && outputTextureTimestamps.element() <= presentationTimeUs) {
      outputTexturePool.freeTexture();
      outputTextureTimestamps.remove();
      GlUtil.deleteSyncObject(syncObjects.remove());
    }
    maybeComposite();
  }

  private void releaseGlObjects() {
    try {
      compositorGlProgram.release();
      outputTexturePool.deleteAllTextures();
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Error releasing GL resources", e);
    }
  }

  /** Holds information on an input source. */
  private static final class InputSource {
    /**
     * A queue of {link FrameInfo}s, monotonically increasing in order of {@code presentationTimeUs}
     * values.
     */
    private final Queue<FrameInfo> frameInfos;

    public boolean isInputEnded;

    public InputSource() {
      frameInfos = new ArrayDeque<>();
    }
  }

  /** Holds information on a frame and how to release it. */
  private static final class FrameInfo {
    public final int inputIndex;
    public final GlTextureProducer textureProducer;
    public final TimedGlTextureInfo timedGlTextureInfo;
    public final OverlaySettings overlaySettings;

    private FrameInfo(
        int inputIndex,
        GlTextureProducer textureProducer,
        TimedGlTextureInfo timedGlTextureInfo,
        OverlaySettings overlaySettings) {
      this.inputIndex = inputIndex;
      this.textureProducer = textureProducer;
      this.timedGlTextureInfo = timedGlTextureInfo;
      this.overlaySettings = overlaySettings;
    }
  }
}
