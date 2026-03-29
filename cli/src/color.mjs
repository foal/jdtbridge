// Colored output with TTY auto-detection.
// Enable: --color flag, JDTBRIDGE_COLOR=1, or FORCE_COLOR=1
// Disable: --no-color flag, NO_COLOR=1

import { createColors } from "picocolors";

const forced = createColors(true);

let _enabled;

/** Check if color output is enabled. */
export function isColorEnabled() {
  if (_enabled !== undefined) return _enabled;

  if (process.env.NO_COLOR || process.argv.includes("--no-color")) {
    _enabled = false;
  } else if (
    process.env.FORCE_COLOR ||
    process.env.JDTBRIDGE_COLOR ||
    process.argv.includes("--color")
  ) {
    _enabled = true;
  } else {
    _enabled = process.stdout.isTTY === true;
  }
  return _enabled;
}

/** Set color enabled/disabled explicitly. */
export function setColorEnabled(enabled) {
  _enabled = enabled;
}

function wrap(fn) {
  return (s) => (isColorEnabled() ? fn(s) : s);
}

export const red = wrap(forced.red);
export const green = wrap(forced.green);
export const yellow = wrap(forced.yellow);
export const bold = wrap(forced.bold);
export const dim = wrap(forced.dim);
