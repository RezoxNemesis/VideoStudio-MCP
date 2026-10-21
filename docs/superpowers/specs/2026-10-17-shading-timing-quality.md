# Editable shading and animation timing

The owner rejects the flat artwork and weak animation in the native story examples. The first reusable quality improvements are material shading and controllable timing, followed by a detailed editable eye study from the reference family. This is a renderer/authoring improvement, not a claim of automatic professional illustration or arbitrary new-pose generation.

- Retain all existing linear gradients and linear/hold keys unchanged.
- Add native multi-stop radial gradients to editable vector shapes. Coordinates use the full canvas, radius uses the smaller canvas dimension. Validate 2–16 colours, optional matching strictly increasing stops with endpoints 0 and 1, and positive radius before mutation.
- Add ease-in, ease-out, ease-in-out, smooth, and explicit cubic-Bezier timing. Bezier controls are four finite values in 0–1. Solve the time coordinate before sampling the output coordinate; never use time directly as the curve parameter. Curves belong only to Bezier keys.
- Use the same timing implementation for drawing object/layer keys and prepared rig/camera keys, preserving authored geometry and existing hold behavior.
- Touch controls and MCP use the same document operations. Native 4.6.0/code 470 advertises this new contract; an older runtime must refuse unsupported fields.
- Render the actual detailed eye study and additional independent material/timing subjects through native Canvas in the workspace. Preserve editable shapes/cels and compare output visually. Keep pixel-change and numerical checks separate from artistic acceptance.
- The owner's new rose portrait is reserved privately for future animation validation, alongside independently chosen subjects. Do not animate it now or put it in Git.

No signed APK or phone acceptance is possible with the currently missing original signing key and attached device. Workspace-rendered images must identify the actual renderer and host.
