// The 2D library: every category's generator. build.mjs writes them out; the film kit and the app read the index.
import { fx } from './fx.js';
import { props } from './props.js';
import { backgrounds } from './backgrounds.js';
import { characters } from './characters.js';
import { hands } from './hands.js';
import { faces } from './faces.js';
import { ui } from './ui.js';
import { textures } from './textures.js';
import { pixel } from './pixel.js';
import { stick } from './stick.js';
export const GENERATORS = { fx, props, backgrounds, characters, hands, faces, ui, textures, pixel, stick };
