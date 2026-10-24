/*
 * Copyright 2026 VideoStudio contributors
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

import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/** Opt-in frame ordering and output-clock settings for the pinned native compositor. */
@UnstableApi
public interface VideoCompositorFramePolicy extends VideoCompositorSettings {
  /**
   * Returns every supplied actual input ID exactly once, in TOP-TO-BOTTOM order.
   *
   * <p>The input is an immutable snapshot in original primary-first order. Only the
   * GL draw list is reordered; primary frame selection/timestamps, input-size order,
   * source textures and release/fence ownership remain unchanged. Invalid orders
   * fail through the compositor's normal error path before leasing output textures.
   */
  List<Integer> getInputIdsInTopToBottomOrder(List<Integer> inputIds);

  /**
   * Excludes gap-phase source frames from an active output interval.
   *
   * <p>Source and output timestamps are distinct. This hook is intended for one
   * contiguous active source interval: when output is active, accept real source
   * timestamps in that interval; otherwise accept every timestamp. Do not use an
   * arbitrary/nonmonotonic predicate: the existing bounded queues retain only the
   * latest frame at/before the primary target. No eligible frame in the available
   * lookahead bracket fails clearly rather than stalling or exposing a black gap.
   *
   * <p>Callbacks must be pure, nonblocking and must not release textures. Frames
   * excluded from selection remain owned by their original producer/queue.
   */
  default boolean isInputFrameEligibleForOutput(
      int inputId, long sourcePresentationTimeUs, long outputPresentationTimeUs) {
    return true;
  }

  /**
   * Settings for a selected source texture at the primary OUTPUT timestamp.
   *
   * <p>The default evaluates ordinary settings on the output clock. A consumer can
   * also inspect the selected source timestamp without conflating the two clocks.
   * The opted-in compositor does not call ordinary settings when queuing frames.
   */
  default OverlaySettings getOverlaySettingsForOutput(
      int inputId, long sourcePresentationTimeUs, long outputPresentationTimeUs) {
    return getOverlaySettings(inputId, outputPresentationTimeUs);
  }
}
