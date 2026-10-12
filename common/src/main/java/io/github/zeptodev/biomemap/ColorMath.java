package io.github.zeptodev.biomemap;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Color helpers. Free of Minecraft classes so they can be tested on their own. */
final class ColorMath {
    private ColorMath() {}

    /** Shifts hue, saturation and lightness a little, deterministically from {@code key}. */
    static int vary(int rgb, String key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        float[] hsl = toHsl(rgb);
        float hue = hsl[0] + ((h & 0xff) / 255f - 0.5f) * 0.08f;
        float saturation = clamp(hsl[1] + (((h >>> 8) & 0xff) / 255f - 0.5f) * 0.2f, 0f, 1f);
        float lightness = clamp(hsl[2] + (((h >>> 16) & 0xff) / 255f - 0.5f) * 0.16f, 0.06f, 0.94f);
        return fromHsl(hue - (float) Math.floor(hue), saturation, lightness);
    }

    /** A strong, readable color picked by a hash of {@code key}, for things with no color of their own. */
    static int hashColor(String key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return fromHsl((h & 0xffff) / 65536f, 0.6f, 0.47f);
    }

    /** Fallback for biomes without useful tags: tan when dry, green when wet, warmer when hot. */
    static int climate(float temperature, float downfall) {
        float t = clamp((temperature + 0.5f) / 2.5f, 0f, 1f);
        int dry = lerp(0xbfc48a, 0xd9b45f, t);
        int wet = lerp(0x3f7d4f, 0x2f7d12, t);
        return lerp(dry, wet, clamp(downfall, 0f, 1f));
    }

    static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * t);
        int g = Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * t);
        int bl = Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * t);
        return r << 16 | g << 8 | bl;
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xffffff);
    }

    /** Accepts {@code #rrggbb}, {@code rrggbb}, {@code 0xrrggbb} and {@code #rgb}; null if invalid. */
    static Integer parseHex(String text) {
        String s = text.strip();
        if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() == 3) {
            s = new String(new char[] {s.charAt(0), s.charAt(0), s.charAt(1), s.charAt(1), s.charAt(2), s.charAt(2)});
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses {@code <id> = <color>} lines; blank lines and lines starting with '#' are skipped. */
    static Map<String, Integer> parseOverrides(List<String> lines, Consumer<String> invalidLine) {
        Map<String, Integer> colors = new HashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            Integer color = eq > 0 ? parseHex(line.substring(eq + 1)) : null;
            if (color == null) {
                invalidLine.accept(line);
            } else {
                colors.put(line.substring(0, eq).strip(), color);
            }
        }
        return colors;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static float[] toHsl(int rgb) {
        float r = ((rgb >> 16) & 0xff) / 255f;
        float g = ((rgb >> 8) & 0xff) / 255f;
        float b = (rgb & 0xff) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        if (max == min) {
            return new float[] {0f, 0f, l};
        }
        float d = max - min;
        float s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
        float h;
        if (max == r) {
            h = (g - b) / d + (g < b ? 6f : 0f);
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        return new float[] {h / 6f, s, l};
    }

    private static int fromHsl(float h, float s, float l) {
        if (s == 0f) {
            int v = Math.round(l * 255f);
            return v << 16 | v << 8 | v;
        }
        float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
        float p = 2f * l - q;
        int r = Math.round(hueToChannel(p, q, h + 1f / 3f) * 255f);
        int g = Math.round(hueToChannel(p, q, h) * 255f);
        int b = Math.round(hueToChannel(p, q, h - 1f / 3f) * 255f);
        return r << 16 | g << 8 | b;
    }

    private static float hueToChannel(float p, float q, float t) {
        if (t < 0f) {
            t += 1f;
        }
        if (t > 1f) {
            t -= 1f;
        }
        if (t < 1f / 6f) {
            return p + (q - p) * 6f * t;
        }
        if (t < 0.5f) {
            return q;
        }
        if (t < 2f / 3f) {
            return p + (q - p) * (2f / 3f - t) * 6f;
        }
        return p;
    }
}
