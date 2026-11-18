# Cine 3D — the guide

(First edition of the engine inside VideoStudio 2. The full recipe of "The Long Road to VI" follows in the next update.)

Cine 3D draws 2.5D films with real depth: painted layers standing at true distances, toon bodies driven by captured
human motion, one real lens (focus, cranes, orbits, dolly-zoom), weather, light moods, bloom and grade. Frames are drawn
on the PC's graphics card in a hidden window and encoded in five-second pieces.

Commands: cine.list, cine.new, cine.open, cine.read, cine.write, cine.frame, cine.sheet, cine.render, cine.progress,
cine.join, cine.stop, cine.tool.

A film folder (Videos/VideoStudio/Cine/<name>): film.js (shots), data/mo/*.bin (performances), data/marks.json,
audio.wav. Shared assets: Videos/VideoStudio/Cine/assets (data/ bodies, tex/ textures, fonts/).
