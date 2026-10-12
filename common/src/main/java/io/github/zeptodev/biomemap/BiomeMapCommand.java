package io.github.zeptodev.biomemap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * <pre>
 * /biomemap                                         map 2048 blocks around you
 * /biomemap [at &lt;x z&gt;] &lt;radius&gt; [&lt;step&gt;] [surface | exact | y &lt;level&gt;]
 * /biomemap cancel
 * </pre>
 * The map covers the square from center-radius to center+radius. {@code step} is how many blocks one
 * pixel covers (a multiple of 4, because Minecraft stores biomes in 4x4x4 cells); it is picked
 * automatically when left out.
 */
final class BiomeMapCommand {
    private static final int DEFAULT_RADIUS = 2048;
    private static final int MAX_RADIUS = 65536;
    /** Largest image side. Bigger canvases fail to display in some browsers (Safari caps at 4096x4096). */
    private static final int MAX_SIZE = 4096;
    /** Image side the automatic step aims for. Exact mode runs the terrain generator per sample, so it aims lower. */
    private static final int AUTO_SIZE = 1024;
    private static final int AUTO_SIZE_EXACT = 512;
    private static final int AUTO_STEP = 0;

    @FunctionalInterface
    private interface Center {
        int[] resolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private BiomeMapCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Center here = ctx -> {
            Vec3 pos = ctx.getSource().getPosition();
            return new int[] {Mth.floor(pos.x), Mth.floor(pos.z)};
        };
        Center at = ctx -> {
            ColumnPos pos = ColumnPosArgument.getColumnPos(ctx, "center");
            return new int[] {pos.x(), pos.z()};
        };
        dispatcher.register(Commands.literal("biomemap")
                .requires(BiomeMapCommand::canUse)
                .executes(ctx -> start(ctx, here, DEFAULT_RADIUS, AUTO_STEP, MapJob.Mode.SURFACE, 0))
                .then(Commands.literal("cancel").executes(BiomeMapCommand::cancel))
                .then(Commands.literal("at")
                        .then(Commands.argument("center", ColumnPosArgument.columnPos()).then(sizeArguments(at))))
                .then(sizeArguments(here)));
    }

    /** Builds {@code <radius> [<step>] [surface | exact | y <level>]}. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> sizeArguments(Center center) {
        // Brigadier copies a child when it is attached, so each node is finished before then() is called.
        RequiredArgumentBuilder<CommandSourceStack, Integer> step =
                Commands.argument("step", IntegerArgumentType.integer(4, 1024));
        step.executes(ctx -> start(ctx, center, radius(ctx), step(ctx), MapJob.Mode.SURFACE, 0));
        addModes(step, center, true);

        RequiredArgumentBuilder<CommandSourceStack, Integer> radius =
                Commands.argument("radius", IntegerArgumentType.integer(16, MAX_RADIUS));
        radius.executes(ctx -> start(ctx, center, radius(ctx), AUTO_STEP, MapJob.Mode.SURFACE, 0));
        addModes(radius, center, false);
        radius.then(step);
        return radius;
    }

    private static void addModes(ArgumentBuilder<CommandSourceStack, ?> parent, Center center, boolean hasStep) {
        parent.then(Commands.literal("surface").executes(ctx ->
                start(ctx, center, radius(ctx), hasStep ? step(ctx) : AUTO_STEP, MapJob.Mode.SURFACE, 0)));
        parent.then(Commands.literal("exact").executes(ctx ->
                start(ctx, center, radius(ctx), hasStep ? step(ctx) : AUTO_STEP, MapJob.Mode.EXACT, 0)));
        parent.then(Commands.literal("y").then(Commands.argument("level", IntegerArgumentType.integer(-2048, 4096))
                .executes(ctx -> start(ctx, center, radius(ctx), hasStep ? step(ctx) : AUTO_STEP,
                        MapJob.Mode.FIXED, IntegerArgumentType.getInteger(ctx, "level")))));
    }

    private static int radius(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "radius");
    }

    private static int step(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "step");
    }

    /**
     * Operators can always use the command. In singleplayer the world owner can use it even with cheats
     * off: it only reads world generation, much like {@code /seed}, which is also allowed there.
     */
    private static boolean canUse(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null
                && !source.getServer().isDedicatedServer()
                && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    private static int start(CommandContext<CommandSourceStack> ctx, Center center, int radius, int step,
                             MapJob.Mode mode, int fixedY) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (step == AUTO_STEP) {
            step = smallestStep(radius, mode == MapJob.Mode.EXACT ? AUTO_SIZE_EXACT : AUTO_SIZE);
        }
        if (step % 4 != 0) {
            source.sendFailure(Component.literal("The step must be a multiple of 4 (Minecraft stores biomes in "
                    + "4x4x4 block cells), for example 4, 8, 16 or 32."));
            return 0;
        }
        int size = Math.ceilDiv(2 * radius, step);
        if (size > MAX_SIZE) {
            source.sendFailure(Component.literal("That map would be " + size + "x" + size + " pixels, but the limit is "
                    + MAX_SIZE + ". Use a step of at least " + smallestStep(radius, MAX_SIZE) + "."));
            return 0;
        }
        int[] c = center.resolve(ctx);
        String error = MapJob.start(source, new MapJob.Settings(c[0], c[1], radius, step, mode, fixedY));
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    /** Smallest multiple of 4 that keeps the image at most {@code maxSize} pixels wide. */
    private static int smallestStep(int radius, int maxSize) {
        int step = 4;
        while (Math.ceilDiv(2 * radius, step) > maxSize) {
            step += 4;
        }
        return step;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        if (MapJob.cancelRunning()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Cancelling the biome map..."), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No biome map is being generated."));
        return 0;
    }
}
