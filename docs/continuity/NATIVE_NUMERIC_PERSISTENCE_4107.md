# Native numeric persistence repair — 4.10.7 integration

Native Java callers can put whole-valued `Double` values such as `2.0` into
`JSONObject`. Its serialized JSON stores those values as `2`. Existing canonical
request hashing deliberately distinguishes the live Java values. Changing that
global behavior would also change unrelated operation fingerprints.

Two persistence boundaries used the live representation for published hashes:

- Animation save hashed the live program but stored a serialized copy. Direct
  native programs then failed reopen/bake with
  `SKETCH_ANIMATION_SNAPSHOT_CHANGED`. The existing Duel example menu actually
  exercised this failure. The ordinary animation editor already copied its UI
  snapshot before save and was protected; ordinary JSON-parsed integer requests
  were protected as well.
- Native reference-part pivot mapping can compute whole-valued `Double` anchors
  at canvas edges. Its import receipt published a document hash different from
  the document actually saved and reopened.

Animation save now takes its existing serialized program copy before validation,
compilation and program hashing. The original request remains intact for its
existing operation fingerprint. New sketch receipts hash a serialized document
copy matching persisted JSON. Global canonicalization and old saved receipts are
unchanged. This repairs newly saved records; it does not silently rewrite or
claim repair of historical failed records.

## Regression evidence

Private logs and JUnit remain outside the repository under
`/workspace/work/character-cutouts-20261021`.

The first new numeric run accidentally used a 128px source below the app's 256px
minimum and produced three fixture errors. Its log is preserved; that run does
not establish the requested regression. Only the fixture size was corrected.

The fresh unchanged four-case numeric RED then produced two passes and two
failures: integer input and the current UI snapshot passed, direct whole-Double
input and actual native Duel creation failed snapshot verification. Saved
evidence separately established that the source hash and original request
fingerprint matched, isolating the program hash mismatch. The source-edge pivot
RED separately failed its exact persisted-document-hash assertion. No assertion
was weakened or removed.

The root agent ran the fresh affected Android suite through the single local
Gradle lane: 151 tests, zero failures, zero errors and one skip. Selectors were
`*SketchAnimation*`, `*SketchReference*Test` and `*SketchCoreTest`, including the
four numeric cases and exact corner-pivot assertion. JUnit is preserved in
`numeric-wire-green-junit`. Full integrated release/CI verification remains owned
by root. Private native actor studies remain source-assisted artwork and require
separate visual review; passing persistence tests cannot accept their likeness,
joints or animation.
