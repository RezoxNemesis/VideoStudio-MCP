# Watching a drawing in Sketch Studio

Video studio 4.1.1 posts a drawing notification after the session baseline and
metadata are saved. Tapping it opens that exact sketch and session, including
when Sketch Studio was closed or showing a different book. Playback starts from
the saved baseline, presents the recorded operations in order, and catches up
with incoming work. Completed sessions can be replayed while their journal is
retained. A missing journal is an error, not evidence of stroke-by-stroke work.

The ChatGPT hand follows published stroke prefixes and selected tools. It is
presentation only and is excluded from artwork exports. Waiting, preparation
and replay are not proof that a human is currently watching.

Android must allow notifications for the app and its **Live drawing** channel.
Sketch Studio → More → Drawing alerts shows availability and opens the relevant
permission/settings flow. Alerts never block drawing or silently grant Android
permissions. A `sketch_live_begin` result reports notification availability and
whether a notification was posted; posting does not prove delivery or viewing.

## Drawing method

For a request to draw from scratch, begin on a blank sketch with:

```json
{
  "sketchId": "my_drawing",
  "operationId": "drawing_session",
  "expectedRevision": 1,
  "drawingMethod": "original_strokes",
  "strokeDurationMs": 500
}
```

`original_strokes` accepts authored paths, lines, shapes, fills and ordinary paint
or erase strokes. Reference guides must remain hidden, including their keyed
opacity. Clone painting, automatic reference tracing, copying existing cels,
image/snapshot insertion and unrecorded document replacement are rejected.
Each accepted mutation has a live event. Starting another session cannot bypass
an unended original-strokes session. **Draw myself** ends that protected session
before adopting the current drawing for normal human editing.

`source_assisted` allows reference-assisted painting and existing artwork. A clone
brush copies source pixels into a real paint cel; a brush journal does not turn
that into an original redraw. Historical or unlabelled sessions use this method
for compatibility. Both notifications and live status identify the method.

Neither mode infers an original artist's stroke sequence, guarantees exact
likeness, or provides learned reconstruction of an unseen pose. Artistic quality
requires inspection of actual native output; successful commands and a hand
animation cannot establish it.

Keep the existing package and pinned signing certificate when installing this
update. Native notification delivery and phone playback need separate device
verification after installation; automated Android tests are not that evidence.
