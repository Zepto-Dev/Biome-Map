package io.github.zeptodev.biomemap;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes the PNG and the self-contained HTML viewer. Free of Minecraft classes so it can be tested on
 * its own.
 */
final class MapExport {
    /** One legend entry. {@code grid} values index into the legend list. */
    record Biome(String id, String name, int color, long count, boolean water) {}

    /** spawnX/spawnZ are null outside the Overworld. */
    record MapInfo(String seed, String dimension, String mode, int fixedY, boolean exactHeights,
                   int originX, int originZ, int step, int width, int height, int centerX, int centerZ,
                   Integer spawnX, Integer spawnZ, String minecraftVersion, String modVersion,
                   String generated, long elapsedMs) {}

    /** A structure set: the "type" players filter by (Villages, Ruined Portals, ...). */
    record StructureGroup(String id, String name, String mod, String modName) {}

    /** One structure of a group (Village Plains, Village Desert, ...) and its marker. */
    record StructureKind(String id, String name, int group, int color, String label) {}

    /**
     * Structures found in the map. {@code points} holds four ints per structure: kind index, then X, Y and Z
     * of its generation point. {@code skipped} and {@code failed} are group names left out because they are
     * too common for the map size or because a check threw an error.
     */
    record Structures(List<StructureGroup> groups, List<StructureKind> kinds, int[] points, List<String> skipped,
                      List<String> failed) {}

    static final String DATA_PLACEHOLDER = "@@BIOMEMAP_DATA@@";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private MapExport() {}

    /** One pixel per sample. Uses a palette PNG when there are at most 256 biomes, otherwise RGB. */
    static void writePng(Path file, int width, int height, short[] grid, int[] colors) throws IOException {
        boolean indexed = colors.length <= 256;
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(pixels)) {
            byte[] line = new byte[1 + width * (indexed ? 1 : 3)];
            for (int y = 0; y < height; y++) {
                int row = y * width;
                if (indexed) {
                    line[0] = 0; // filter: none
                    for (int x = 0; x < width; x++) {
                        line[1 + x] = (byte) grid[row + x];
                    }
                } else {
                    line[0] = 1; // filter: sub, each byte minus the one a pixel to the left
                    int pr = 0;
                    int pg = 0;
                    int pb = 0;
                    for (int x = 0; x < width; x++) {
                        int c = colors[grid[row + x]];
                        int r = (c >> 16) & 0xff;
                        int g = (c >> 8) & 0xff;
                        int b = c & 0xff;
                        line[1 + x * 3] = (byte) (r - pr);
                        line[2 + x * 3] = (byte) (g - pg);
                        line[3 + x * 3] = (byte) (b - pb);
                        pr = r;
                        pg = g;
                        pb = b;
                    }
                }
                out.write(line);
            }
        }

        ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(width).putInt(height);
        header.put((byte) 8).put((byte) (indexed ? 3 : 2)).put((byte) 0).put((byte) 0).put((byte) 0);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.write(PNG_SIGNATURE);
            writeChunk(out, "IHDR", header.array());
            if (indexed) {
                byte[] palette = new byte[colors.length * 3];
                for (int i = 0; i < colors.length; i++) {
                    palette[i * 3] = (byte) (colors[i] >> 16);
                    palette[i * 3 + 1] = (byte) (colors[i] >> 8);
                    palette[i * 3 + 2] = (byte) colors[i];
                }
                writeChunk(out, "PLTE", palette);
            }
            writeChunk(out, "IDAT", pixels.toByteArray());
            writeChunk(out, "IEND", new byte[0]);
        }
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    /**
     * The map as JSON for the viewer. The biome grid and heights are little-endian 16-bit arrays,
     * zlib-compressed and base64-encoded; heights are stored as differences along each row, which
     * compresses far better. {@code heights} and {@code structures} may be null.
     */
    static String toJson(MapInfo info, List<Biome> biomes, short[] grid, short[] heights, Structures structures) {
        StringBuilder json = new StringBuilder(256 + biomes.size() * 96);
        json.append('{');
        field(json, "format").append(1);
        field(json, "seed");
        string(json, info.seed());
        field(json, "dimension");
        string(json, info.dimension());
        field(json, "mode");
        string(json, info.mode());
        field(json, "y").append(info.fixedY());
        field(json, "heightKind");
        if (heights == null) {
            json.append("null");
        } else {
            string(json, info.exactHeights() ? "exact" : "estimated");
        }
        field(json, "originX").append(info.originX());
        field(json, "originZ").append(info.originZ());
        field(json, "step").append(info.step());
        field(json, "width").append(info.width());
        field(json, "height").append(info.height());
        field(json, "centerX").append(info.centerX());
        field(json, "centerZ").append(info.centerZ());
        field(json, "spawn");
        if (info.spawnX() == null || info.spawnZ() == null) {
            json.append("null");
        } else {
            json.append("{\"x\":").append(info.spawnX()).append(",\"z\":").append(info.spawnZ()).append('}');
        }
        field(json, "minecraft");
        string(json, info.minecraftVersion());
        field(json, "modVersion");
        string(json, info.modVersion());
        field(json, "generated");
        string(json, info.generated());
        field(json, "elapsedMs").append(info.elapsedMs());
        field(json, "biomes").append('[');
        for (int i = 0; i < biomes.size(); i++) {
            Biome biome = biomes.get(i);
            json.append(i == 0 ? "{" : ",{");
            json.append("\"id\":");
            string(json, biome.id());
            json.append(",\"name\":");
            string(json, biome.name());
            json.append(",\"color\":");
            string(json, ColorMath.hex(biome.color()));
            json.append(",\"count\":").append(biome.count());
            json.append(",\"water\":").append(biome.water());
            json.append('}');
        }
        json.append(']');
        field(json, "grid");
        string(json, pack(grid));
        field(json, "heightData");
        if (heights == null) {
            json.append("null");
        } else {
            string(json, pack(rowDeltas(heights, info.width(), info.height())));
        }
        field(json, "structures");
        if (structures == null) {
            json.append("null");
        } else {
            structuresJson(json, structures);
        }
        return json.append('}').toString();
    }

    private static void structuresJson(StringBuilder json, Structures structures) {
        json.append("{\"groups\":[");
        for (int i = 0; i < structures.groups().size(); i++) {
            StructureGroup group = structures.groups().get(i);
            json.append(i == 0 ? "{" : ",{").append("\"id\":");
            string(json, group.id());
            json.append(",\"name\":");
            string(json, group.name());
            json.append(",\"mod\":");
            string(json, group.mod());
            json.append(",\"modName\":");
            string(json, group.modName());
            json.append('}');
        }
        json.append("],\"kinds\":[");
        for (int i = 0; i < structures.kinds().size(); i++) {
            StructureKind kind = structures.kinds().get(i);
            json.append(i == 0 ? "{" : ",{").append("\"id\":");
            string(json, kind.id());
            json.append(",\"name\":");
            string(json, kind.name());
            json.append(",\"group\":").append(kind.group()).append(",\"color\":");
            string(json, ColorMath.hex(kind.color()));
            json.append(",\"label\":");
            string(json, kind.label());
            json.append('}');
        }
        json.append("],\"points\":[");
        int[] points = structures.points();
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(points[i]);
        }
        json.append("],\"skipped\":[");
        stringList(json, structures.skipped());
        json.append("],\"failed\":[");
        stringList(json, structures.failed());
        json.append("]}");
    }

    private static void stringList(StringBuilder json, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            string(json, values.get(i));
        }
    }

    static void writeHtml(Path file, String template, String json) throws IOException {
        int at = template.indexOf(DATA_PLACEHOLDER);
        if (at < 0) {
            throw new IOException("The viewer template has no " + DATA_PLACEHOLDER + " placeholder");
        }
        String html = template.substring(0, at) + json + template.substring(at + DATA_PLACEHOLDER.length());
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    private static StringBuilder field(StringBuilder json, String name) {
        if (json.length() > 1) {
            json.append(',');
        }
        return json.append('"').append(name).append("\":");
    }

    /** JSON string. Also escapes '<', '>' and '&' because the JSON is embedded in a script tag. */
    private static void string(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '<', '>', '&', ' ', ' ' -> json.append(String.format("\\u%04x", (int) c));
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    private static short[] rowDeltas(short[] values, int width, int height) {
        short[] deltas = new short[values.length];
        for (int y = 0; y < height; y++) {
            int previous = 0;
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                deltas[i] = (short) (values[i] - previous);
                previous = values[i];
            }
        }
        return deltas;
    }

    private static String pack(short[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(values);
        return Base64.getEncoder().encodeToString(deflate(bytes.array()));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length / 16));
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
