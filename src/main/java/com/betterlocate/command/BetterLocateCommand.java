package com.betterlocate.command;

import com.betterlocate.BetterLocate;
import com.google.common.base.Stopwatch;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.ChatFormatting;
// 版本差异：1.21.11 起 Util 从 net.minecraft 挪到了 net.minecraft.util
//? if >=1.21.11 {
/*import net.minecraft.util.Util;*/
//?} else {
import net.minecraft.Util;
//?}
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增强版 {@code /locate}：参数和原版完全兼容，另外多了三个可选的修饰参数。
 *
 * <pre>
 * /locate structure &lt;结构&gt; [radius &lt;格&gt;] [center &lt;x y z&gt;] [chunks visited|unvisited|any]
 * /locate biome     &lt;生物群系&gt; [radius ...] [center ...] [chunks ...]
 * /locate poi       &lt;兴趣点&gt; [radius ...] [center ...] [chunks ...]
 * </pre>
 *
 * <ul>
 *   <li>三个修饰参数顺序随意，可以任意组合，也可以都不写（那就和不装本模组时一模一样）；</li>
 *   <li>搜索一开始会在控制台和聊天栏输出「正在搜索 xx 中……」；</li>
 *   <li>加了 radius 才进入「范围统计」：以 center 为圆心、多少格为半径，
 *       生物群系给出属于它的区块数量，结构 / 兴趣点给出数量并按由近到远列出坐标；</li>
 *   <li>不加 radius 就是原版语义：只找最近的一个，不会去扫一个范围；</li>
 *   <li>chunks 用来看「玩家到访过没有」：visited = 到访过的区块（存档里已经有这一格），
 *       unvisited = 从来没去过的区块（存档里根本没有）；不加 radius 时也照样生效
 *       （此时找的是「允许的区块里最近的那一个」）；</li>
 *   <li>center 用来指定搜索中心，默认是命令执行者所在的位置。</li>
 *   <li>所有坐标都可以直接点：点坐标或者点后面的「[点击传送]」就会传送过去
 *       （原版是把 tp 命令填进输入框，这里更进一步）。</li>
 * </ul>
 *
 * <p>关于并行：生物群系取样是纯计算（原版本体生成要塞时也是在后台线程里这么做的），
 * 所以按区块切片并行跑；结构搜索则把「哪些区块有可能生成结构」这一步拆出来
 * ——那是纯数学计算，可以并行——真正读取世界、计算结构起点的部分仍然交回原版
 * {@code findNearestMapStructure} 在主线程完成，避免多线程碰区块缓存导致数据竞争。
 */
public final class BetterLocateCommand
{
    private static final DynamicCommandExceptionType ERROR_STRUCTURE_INVALID = new DynamicCommandExceptionType(
            value -> Component.translatableEscape("commands.locate.structure.invalid", value));
    private static final DynamicCommandExceptionType ERROR_STRUCTURE_NOT_FOUND = new DynamicCommandExceptionType(
            value -> Component.translatableEscape("commands.locate.structure.not_found", value));
    private static final DynamicCommandExceptionType ERROR_BIOME_NOT_FOUND = new DynamicCommandExceptionType(
            value -> Component.translatableEscape("commands.locate.biome.not_found", value));
    private static final DynamicCommandExceptionType ERROR_POI_NOT_FOUND = new DynamicCommandExceptionType(
            value -> Component.translatableEscape("commands.locate.poi.not_found", value));
    private static final DynamicCommandExceptionType ERROR_RADIUS_TOO_LARGE = new DynamicCommandExceptionType(
            value -> Component.literal("搜索范围太大了：这次要检查 " + value + " 个区块。请把 radius 改小一些，"
                    + "或者加上 chunks visited / chunks unvisited 只扫一部分区块。"));

    /** 并行搜索用的线程池：只跑纯计算（生物群系取样、候选区块枚举），不碰世界数据 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
            runnable -> {
                Thread thread = new Thread(runnable, "BetterLocate-Search");
                thread.setDaemon(true);
                return thread;
            });

    /** 结果里最多列多少个坐标，免得聊天栏刷屏 */
    private static final int MAX_LISTED = 30;
    /** 结构搜索最多判定多少个候选区块（保护措施，避免 radius 填太大把服务器卡死） */
    private static final int MAX_STRUCTURE_CANDIDATES = 20_000;
    /** 生物群系搜索最多取样多少个区块 */
    private static final int MAX_BIOME_SAMPLES = 1_000_000;
    /** 兴趣点最多返回多少个 */
    private static final int MAX_POI_RESULTS = 8_192;
    /** 原版的搜索上限，保持兼容 */
    private static final int VANILLA_STRUCTURE_SEARCH_CHUNKS = 100;
    private static final int VANILLA_BIOME_SEARCH_RADIUS = 6400;
    private static final int VANILLA_POI_SEARCH_RADIUS = 256;
    /**
     * 参数节点的内部名字。Brigadier 的参数是「位置参数」，名字不写在指令里，
     * 所以玩家写的 {@code radius 1000} 是「literal radius + 一个整数参数」，
     * 名字只是给代码取值用的。
     */
    private static final String ARG_RADIUS = "radius_value";
    private static final String ARG_CENTER = "center_position";

    private BetterLocateCommand()
    {
    }

    // ======================================================================
    // 指令树
    // ======================================================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("locate")
                ;
        // 版本差异：1.21.11 起权限改成 PermissionSet，原版的写法是 Commands.hasPermission(...)
        //? if >=1.21.11 {
        /*root.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));*/
        //?} else {
        root.requires(source -> source.hasPermission(2));
        //?}

        ArgumentBuilder<CommandSourceStack, ?> structure = Commands.argument(
                "structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE));
        attachModifiers(structure, BetterLocateCommand::executeStructure);
        root.then(Commands.literal("structure").then(structure));

        ArgumentBuilder<CommandSourceStack, ?> biome = Commands.argument(
                "biome", ResourceOrTagArgument.resourceOrTag(context, Registries.BIOME));
        attachModifiers(biome, BetterLocateCommand::executeBiome);
        root.then(Commands.literal("biome").then(biome));

        ArgumentBuilder<CommandSourceStack, ?> poi = Commands.argument(
                "poi", ResourceOrTagArgument.resourceOrTag(context, Registries.POINT_OF_INTEREST_TYPE));
        attachModifiers(poi, BetterLocateCommand::executePoi);
        root.then(Commands.literal("poi").then(poi));

        dispatcher.register(root);
    }

    private interface SearchExecutor
    {
        int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    private enum Modifier
    {
        RADIUS, CENTER, CHUNKS
    }

    private static void attachModifiers(ArgumentBuilder<CommandSourceStack, ?> parent, SearchExecutor executor)
    {
        attach(parent, List.of(Modifier.values()), executor);
    }

    /**
     * 把三个修饰参数按「所有排列」挂到指令树上，于是 radius、center、chunks 写在前写在后都能解析。
     * Brigadier 会自动把同名的子节点合并，所以不会真的长出重复的树。
     */
    private static void attach(ArgumentBuilder<CommandSourceStack, ?> parent, List<Modifier> remaining,
                               SearchExecutor executor)
    {
        parent.executes(executor::run);
        for (int i = 0; i < remaining.size(); i++)
        {
            Modifier modifier = remaining.get(i);
            List<Modifier> rest = new ArrayList<>(remaining);
            rest.remove(i);
            switch (modifier)
            {
                case RADIUS ->
                {
                    LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("radius");
                    ArgumentBuilder<CommandSourceStack, ?> value = Commands.argument(
                            ARG_RADIUS, IntegerArgumentType.integer(1, 100_000));
                    attach(value, rest, executor);
                    node.then(value);
                    parent.then(node);
                }
                case CENTER ->
                {
                    LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("center");
                    ArgumentBuilder<CommandSourceStack, ?> value = Commands.argument(
                            ARG_CENTER, BlockPosArgument.blockPos());
                    attach(value, rest, executor);
                    node.then(value);
                    parent.then(node);
                }
                case CHUNKS ->
                {
                    LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("chunks");
                    for (String mode : List.of("visited", "unvisited", "any"))
                    {
                        LiteralArgumentBuilder<CommandSourceStack> branch = Commands.literal(mode);
                        attach(branch, rest, executor);
                        node.then(branch);
                    }
                    parent.then(node);
                }
            }
        }
    }

    // ======================================================================
    // 参数读取
    // ======================================================================

    private record Options(Integer radius, BlockPos center, String chunkFilter)
    {
        static Options of(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
        {
            Integer radius = hasNode(context, ARG_RADIUS)
                    ? IntegerArgumentType.getInteger(context, ARG_RADIUS) : null;
            // 注意：这里刻意不用 getLoadedBlockPos，因为搜索中心完全可以落在还没加载的地方
            BlockPos center = hasNode(context, ARG_CENTER)
                    ? BlockPosArgument.getBlockPos(context, ARG_CENTER) : null;
            String chunkFilter = "any";
            for (var node : context.getNodes())
            {
                String name = node.getNode().getName();
                if (name.equals("visited") || name.equals("unvisited"))
                {
                    chunkFilter = name;
                    break;
                }
            }
            return new Options(radius, center, chunkFilter);
        }
    }

    private static boolean hasNode(CommandContext<?> context, String name)
    {
        return context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals(name));
    }

    /**
     * 「这块地玩家到访过没有」的判定。
     *
     * <p>原版没有「到访过」这种标记，但区块只要被生成过一次（玩家走近、滑翔路过、被结构搜索带出来……），
     * 存档的 region 文件里就会有它；反过来，从来没去过的区块在存档里根本不存在。
     * 所以「region 文件里有没有这一格」正好就是「有没有到访过」。
     *
     * <p>只读每个 region 文件开头的 4KB 定位表（一个 region 管 32×32 个区块），读完缓存住。
     * 注意要在搜索开始前查——搜索本身会把没去过的区块生成出来，边搜边查就不准了。
     */
    private static final class VisitIndex
    {
        private static final int REGION_HEADER_BYTES = 4096;
        private final Path regionDir;
        private final Map<Long, boolean[]> regions = new HashMap<>();

        VisitIndex(ServerLevel level)
        {
            Path root = level.getServer().getWorldPath(LevelResource.ROOT);
            String dimension = dimensionId(level.dimension());
            Path dir;
            if (dimension.equals("minecraft:overworld"))
            {
                dir = root.resolve("region");
            }
            else if (dimension.equals("minecraft:the_nether"))
            {
                dir = root.resolve("DIM-1").resolve("region");
            }
            else if (dimension.equals("minecraft:the_end"))
            {
                dir = root.resolve("DIM1").resolve("region");
            }
            else
            {
                // 别的维度都在 dimensions/<命名空间>/<路径>/region 下
                String[] parts = dimension.split(":", 2);
                dir = root.resolve("dimensions").resolve(parts[0])
                        .resolve(parts.length > 1 ? parts[1] : "").resolve("region");
            }
            this.regionDir = dir;
        }

        boolean visited(int chunkX, int chunkZ)
        {
            int regionX = chunkX >> 5;
            int regionZ = chunkZ >> 5;
            boolean[] slots = this.regions.computeIfAbsent(
                    chunkKey(regionX, regionZ), key -> this.readRegion(regionX, regionZ));
            return slots[(chunkX & 31) + (chunkZ & 31) * 32];
        }

        private boolean[] readRegion(int regionX, int regionZ)
        {
            boolean[] slots = new boolean[1024];
            Path path = this.regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
            if (!Files.isRegularFile(path))
            {
                return slots;
            }
            try (InputStream in = Files.newInputStream(path))
            {
                // 定位表：每 4 字节 = 3 字节扇区偏移 + 1 字节扇区数；偏移为 0 就是这一格没有区块
                byte[] header = in.readNBytes(REGION_HEADER_BYTES);
                for (int i = 0; i < 1024 && i * 4 + 3 < header.length; i++)
                {
                    int offset = ((header[i * 4] & 0xFF) << 16)
                            | ((header[i * 4 + 1] & 0xFF) << 8)
                            | (header[i * 4 + 2] & 0xFF);
                    int sectors = header[i * 4 + 3] & 0xFF;
                    slots[i] = offset != 0 && sectors != 0;
                }
            }
            catch (IOException e)
            {
                BetterLocate.LOGGER.warn("读区块存档失败：{}（这一格按没到访过算）", path, e);
            }
            return slots;
        }
    }

    /** 该区块是否允许参与搜索：visited = 玩家到访过，unvisited = 从来没去过 */
    private static boolean chunkAllowed(VisitIndex visits, int chunkX, int chunkZ, String filter)
    {
        if (filter == null || "any".equals(filter))
        {
            return true;
        }
        boolean visited = visits.visited(chunkX, chunkZ);
        return "visited".equals(filter) == visited;
    }

    /**
     * 列出结果时用的标注：这一格玩家去过没有。
     * 去过 = 绿色【已到访】，没去过 = 灰色【未到访】。
     */
    private static Component visitTag(VisitIndex visits, BlockPos pos)
    {
        boolean visited = visits.visited(pos.getX() >> 4, pos.getZ() >> 4);
        return Component.literal(visited ? "【已到访】" : "【未到访】")
                .withStyle(visited ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    /** 版本差异：1.21.11 起 ResourceKey#location 改叫 identifier，这里统一成字符串用 */
    private static String dimensionId(ResourceKey<Level> key)
    {
        //? if >=1.21.11 {
        /*return key.identifier().toString();*/
        //?} else {
        return key.location().toString();
        //?}
    }

    // ------------------------------------------------------------------
    // 版本差异：26.x 起 ChunkPos 变成了 record，字段改成 x()/z()，
    // 序列化方法也从 toLong/asLong 改名为 pack。下面三个小工具把它们抹平。
    // ------------------------------------------------------------------

    private static int chunkX(ChunkPos pos)
    {
        //? if >=26.1 {
        /*return pos.x();*/
        //?} else {
        return pos.x;
        //?}
    }

    private static int chunkZ(ChunkPos pos)
    {
        //? if >=26.1 {
        /*return pos.z();*/
        //?} else {
        return pos.z;
        //?}
    }

    private static long chunkKey(ChunkPos pos)
    {
        //? if >=26.1 {
        /*return pos.pack();*/
        //?} else {
        return pos.toLong();
        //?}
    }

    private static long chunkKey(int x, int z)
    {
        //? if >=26.1 {
        /*return ChunkPos.pack(x, z);*/
        //?} else {
        return ChunkPos.asLong(x, z);
        //?}
    }

    private static void announce(CommandSourceStack source, String what)
    {
        String message = "正在搜索 " + what + " 中……";
        BetterLocate.LOGGER.info(message);
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GRAY), false);
    }

    private static void feedback(CommandSourceStack source, Component component)
    {
        BetterLocate.LOGGER.info(component.getString());
        source.sendSuccess(() -> component, false);
    }

    private static long millis(Stopwatch stopwatch)
    {
        return stopwatch.elapsed(TimeUnit.MILLISECONDS);
    }

    // ======================================================================
    // 可点击的输出
    // ======================================================================

    /**
     * 坐标组件，点一下直接传送。
     *
     * <p>{@code showRealY} 为假表示这个坐标的 Y 没有意义（结构只能算出 X/Z），
     * 此时显示成 {@code ~}，传送时也只改 X/Z，保留玩家当前高度，和原版一致。
     */
    private static Component coordinate(BlockPos pos, boolean showRealY)
    {
        String y = showRealY ? String.valueOf(pos.getY()) : "~";
        return ComponentUtils.wrapInSquareBrackets(
                        Component.translatable("chat.coordinates", pos.getX(), y, pos.getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(teleportClick(pos, !showRealY))
                        .withHoverEvent(showText(Component.literal("点击传送：" + describe(pos, !showRealY)))));
    }

    /** 单独一个「[安全传送]」按钮：执行 /safetp，自动找到能站得住的高度 */
    private static Component teleportButton(BlockPos pos, boolean keepY)
    {
        return Component.literal("[安全传送]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(safeTpClick(pos))
                        .withHoverEvent(showText(Component.literal("点击执行 " + safeTpCommand(pos)))));
    }

    /** 版本差异：1.21.5 起 HoverEvent 由类变成接口，要用 HoverEvent.ShowText 构造 */
    private static HoverEvent showText(Component text)
    {
        //? if >=1.21.5 {
        /*return new HoverEvent.ShowText(text);*/
        //?} else {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        //?}
    }

    private static ClickEvent teleportClick(BlockPos pos, boolean keepY)
    {
        // 版本差异：1.21.5 起 ClickEvent 也变成了接口，RUN_COMMAND 对应 ClickEvent.RunCommand
        //? if >=1.21.5 {
        /*return new ClickEvent.RunCommand(teleportCommand(pos, keepY));*/
        //?} else {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, teleportCommand(pos, keepY));
        //?}
    }

    private static ClickEvent safeTpClick(BlockPos pos)
    {
        // 版本差异：1.21.5 起 ClickEvent 也变成了接口，RUN_COMMAND 对应 ClickEvent.RunCommand
        //? if >=1.21.5 {
        /*return new ClickEvent.RunCommand(safeTpCommand(pos));*/
        //?} else {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, safeTpCommand(pos));
        //?}
    }

    private static String safeTpCommand(BlockPos pos)
    {
        return String.format("/safetp @s %d %d %d top normal 3", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String teleportCommand(BlockPos pos, boolean keepY)
    {
        return keepY
                ? String.format("/tp @s %d ~ %d", pos.getX(), pos.getZ())
                : String.format("/tp @s %d %d %d", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String describe(BlockPos pos, boolean keepY)
    {
        return keepY
                ? String.format("(%d, ~, %d)（保持当前高度）", pos.getX(), pos.getZ())
                : String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
    }

    /** 原版那种「最近的 xx 在 [坐标]（多少格外）」的输出，区别是坐标点一下就传送 */
    private static int showNearest(CommandSourceStack source, String translationKey, String elementName,
                                   BlockPos center, BlockPos pos, boolean showRealY)
    {
        int distance = distanceTo(center, pos, showRealY);
        Component message = Component.translatable(translationKey, elementName, coordinate(pos, showRealY), distance)
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal("  "))
                .append(teleportButton(pos, !showRealY));
        feedback(source, message);
        return distance;
    }

    private static int distanceTo(BlockPos center, BlockPos pos, boolean absoluteY)
    {
        if (absoluteY)
        {
            return Mth.floor(Math.sqrt(pos.distSqr(center)));
        }
        return Mth.floor(Math.sqrt(Mth.square(pos.getX() - center.getX())
                + Mth.square(pos.getZ() - center.getZ())));
    }

    // ======================================================================
    // 结构
    // ======================================================================

    private static int executeStructure(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // 1.21.2 起 RegistryAccess 的 registryOrThrow 改名为 lookupOrThrow
        //? if >=1.21.2 {
        /*Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);*/
        //?} else {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        //?}
        ResourceOrTagKeyArgument.Result<Structure> target = ResourceOrTagKeyArgument.getResourceOrTagKey(
                context, "structure", Registries.STRUCTURE, ERROR_STRUCTURE_INVALID);
        @SuppressWarnings("unchecked")
        HolderSet<Structure> holders = (HolderSet<Structure>) getHolders(target, registry)
                .orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(target.asPrintable()));
        Options options = Options.of(context);
        BlockPos center = options.center() != null ? options.center() : BlockPos.containing(source.getPosition());
        announce(source, "结构 " + target.asPrintable());

        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        // 到访快照：必须在搜索开始前建，搜索本身会把没去过的区块生成出来
        VisitIndex visits = new VisitIndex(level);
        if (options.radius() == null)
        {
            // 没写 radius：和原版一样只找最近的一个（chunks 只是把可选的区块筛一遍）
            Pair<BlockPos, Holder<Structure>> pair = "any".equals(options.chunkFilter())
                    ? level.getChunkSource().getGenerator()
                            .findNearestMapStructure(level, holders, center, VANILLA_STRUCTURE_SEARCH_CHUNKS, false)
                    : findNearestStructureFiltered(level, holders, center, options.chunkFilter(), visits);
            stopwatch.stop();
            if (pair == null)
            {
                throw ERROR_STRUCTURE_NOT_FOUND.create(target.asPrintable());
            }
            String elementName = target.unwrap().map(
                    key -> target.asPrintable(),
                    tag -> target.asPrintable() + " (" + pair.getSecond().getRegisteredName() + ")");
            int result = showNearest(source, "commands.locate.structure.success", elementName,
                    center, pair.getFirst(), false);
            feedback(source, Component.literal("（用时 " + millis(stopwatch) + " ms）").withStyle(ChatFormatting.DARK_GRAY));
            return result;
        }

        int radius = options.radius();
        List<BlockPos> found = searchStructures(level, holders, center, radius, options.chunkFilter(), visits);
        stopwatch.stop();
        printStats(source, "结构", target.asPrintable(), center, radius, found, stopwatch, true, visits);
        return Math.max(1, found.size());
    }

    private static Optional<? extends HolderSet.ListBacked<Structure>> getHolders(
            ResourceOrTagKeyArgument.Result<Structure> target, Registry<Structure> registry)
    {
        // 1.21.2 起 Registry#getHolder / #getTag 合并成了 RegistryLookup#get
        //? if >=1.21.2 {
        /*return target.unwrap().map(
                key -> registry.get((ResourceKey<Structure>) key).map(HolderSet::direct),
                registry::get);*/
        //?} else {
        return target.unwrap().map(
                key -> registry.getHolder((ResourceKey<Structure>) key).map(HolderSet::direct),
                registry::getTag);
        //?}
    }

    /** 结构放置方式表，原版每次搜索都要现搭一遍，这里统一成一处 */
    private static Map<StructurePlacement, Set<Holder<Structure>>> placementsFor(
            ChunkGeneratorStructureState state, HolderSet<Structure> holders)
    {
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders)
        {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder))
            {
                byPlacement.computeIfAbsent(placement, key -> new LinkedHashSet<>()).add(holder);
            }
        }
        return byPlacement;
    }

    /**
     * 并行（纯计算）枚举范围内所有「有可能生成结构」的候选区块，已去重。
     *
     * <p>随机散布类结构用原版的 {@link RandomSpreadStructurePlacement#getPotentialStructureChunk(long, int, int)}
     * 枚举，同心环类结构（要塞）直接取原版算好的环上位置。
     */
    private static List<ChunkPos> enumerateCandidates(Map<StructurePlacement, Set<Holder<Structure>>> byPlacement,
                                                      ChunkGeneratorStructureState state, long seed,
                                                      int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ)
    {
        BetterLocate.LOGGER.info("并行枚举候选区块：{} 个结构放置方式，{} 个线程",
                byPlacement.size(), Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        List<CompletableFuture<List<ChunkPos>>> futures = new ArrayList<>();
        for (StructurePlacement placement : byPlacement.keySet())
        {
            futures.add(CompletableFuture.supplyAsync(
                    () -> candidateChunks(placement, state, seed, minChunkX, maxChunkX, minChunkZ, maxChunkZ), POOL));
        }
        List<ChunkPos> candidates = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (CompletableFuture<List<ChunkPos>> future : futures)
        {
            for (ChunkPos candidate : future.join())
            {
                if (seen.add(chunkKey(candidate)))
                {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * 指定了 chunks、又没指定 radius 时的结构搜索：在原版那 100 个区块的搜索范围里，
     * 从近到远逐个候选区块判定，返回「允许的区块里最近的那一个」。
     */
    private static Pair<BlockPos, Holder<Structure>> findNearestStructureFiltered(
            ServerLevel level, HolderSet<Structure> holders, BlockPos center, String chunkFilter, VisitIndex visits)
    {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        state.ensureStructuresGenerated();
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = placementsFor(state, holders);
        if (byPlacement.isEmpty())
        {
            return null;
        }
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        List<ChunkPos> candidates = enumerateCandidatesByRings(byPlacement, state, state.getLevelSeed(),
                chunkX, chunkZ, VANILLA_STRUCTURE_SEARCH_CHUNKS);
        // 先按到访快照筛一遍，之后才开始逐个判定（判定会把区块生成出来，不能边判边查）
        candidates.removeIf(candidate -> !chunkAllowed(visits, chunkX(candidate), chunkZ(candidate), chunkFilter));
        candidates.sort(Comparator.comparingDouble((ChunkPos candidate) -> chunkBoxDistSqr(candidate, center)));

        Pair<BlockPos, Holder<Structure>> best = null;
        double bestDist = Double.MAX_VALUE;
        for (ChunkPos candidate : candidates)
        {
            // 后面的候选区块只会更远，已经不可能更近了
            if (best != null && chunkBoxDistSqr(candidate, center) > bestDist)
            {
                break;
            }
            BlockPos probe = new BlockPos(candidate.getMinBlockX(), center.getY(), candidate.getMinBlockZ());
            Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, holders, probe, 0, false);
            if (hit != null && hit.getFirst().distSqr(center) < bestDist)
            {
                bestDist = hit.getFirst().distSqr(center);
                best = hit;
            }
        }
        return best;
    }

    /**
     * 枚举「原版 /locate structure 本来会去找」的那些候选区块。
     *
     * <p>原版的 searchRadius 是**按结构放置间距**向外扩圈：{@code x + spacing * ring}，
     * 所以 100 圈能覆盖到几万格开外，而不是 100 个区块。这里按同样的口径枚举，
     * 保证「没写 radius 时的结果」和原版一致。
     */
    private static List<ChunkPos> enumerateCandidatesByRings(
            Map<StructurePlacement, Set<Holder<Structure>>> byPlacement, ChunkGeneratorStructureState state,
            long seed, int chunkX, int chunkZ, int rings)
    {
        BetterLocate.LOGGER.info("并行枚举候选区块：{} 个结构放置方式，{} 个线程",
                byPlacement.size(), Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        List<CompletableFuture<List<ChunkPos>>> futures = new ArrayList<>();
        for (StructurePlacement placement : byPlacement.keySet())
        {
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<ChunkPos> result = new ArrayList<>();
                if (placement instanceof RandomSpreadStructurePlacement spread)
                {
                    int spacing = Math.max(1, spread.spacing());
                    for (int ringX = -rings; ringX <= rings; ringX++)
                    {
                        for (int ringZ = -rings; ringZ <= rings; ringZ++)
                        {
                            ChunkPos candidate = spread.getPotentialStructureChunk(
                                    seed, chunkX + ringX * spacing, chunkZ + ringZ * spacing);
                            if (spread.applyAdditionalChunkRestrictions(chunkX(candidate), chunkZ(candidate), seed))
                            {
                                result.add(candidate);
                            }
                        }
                    }
                }
                else if (placement instanceof ConcentricRingsStructurePlacement concentric)
                {
                    List<ChunkPos> ringPositions = state.getRingPositionsFor(concentric);
                    if (ringPositions != null)
                    {
                        result.addAll(ringPositions);
                    }
                }
                return result;
            }, POOL));
        }
        List<ChunkPos> candidates = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (CompletableFuture<List<ChunkPos>> future : futures)
        {
            for (ChunkPos candidate : future.join())
            {
                if (seen.add(chunkKey(candidate)))
                {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /** 半径模式的结构搜索：先并行算出候选区块，再逐个交回原版算法判定，最后按距离排序 */
    private static List<BlockPos> searchStructures(ServerLevel level, HolderSet<Structure> holders, BlockPos center,
                                                   int radius, String chunkFilter, VisitIndex visits)
            throws CommandSyntaxException
    {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        state.ensureStructuresGenerated();
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = placementsFor(state, holders);
        if (byPlacement.isEmpty())
        {
            return List.of();
        }

        int chunkRadius = (radius >> 4) + 1;
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        List<ChunkPos> candidates = enumerateCandidates(byPlacement, state, state.getLevelSeed(),
                chunkX - chunkRadius, chunkX + chunkRadius, chunkZ - chunkRadius, chunkZ + chunkRadius);
        if (candidates.size() > MAX_STRUCTURE_CANDIDATES)
        {
            throw ERROR_RADIUS_TOO_LARGE.create(candidates.size());
        }
        if (!"any".equals(chunkFilter))
        {
            // 先按到访快照筛一遍，再开始判定（判定会生成区块，不能边判边查）
            candidates.removeIf(candidate -> !chunkAllowed(visits, chunkX(candidate), chunkZ(candidate), chunkFilter));
        }
        candidates.sort(Comparator.comparingDouble((ChunkPos candidate) -> chunkCenterDistSqr(candidate, center)));

        List<BlockPos> found = new ArrayList<>();
        Set<Long> foundKeys = new LinkedHashSet<>();
        long radiusSqr = (long) radius * radius;
        int index = 0;
        for (ChunkPos candidate : candidates)
        {
            BlockPos probe = new BlockPos(candidate.getMinBlockX(), center.getY(), candidate.getMinBlockZ());
            Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, holders, probe, 0, false);
            if (hit != null && hit.getFirst().distSqr(center) <= radiusSqr && foundKeys.add(hit.getFirst().asLong()))
            {
                found.add(hit.getFirst());
            }
            if (++index % 512 == 0)
            {
                BetterLocate.LOGGER.info("正在搜索中……已判定 {}/{} 个候选区块", index, candidates.size());
            }
        }
        found.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)));
        return found;
    }

    private static List<ChunkPos> candidateChunks(StructurePlacement placement, ChunkGeneratorStructureState state,
                                                  long seed, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ)
    {
        List<ChunkPos> result = new ArrayList<>();
        if (placement instanceof RandomSpreadStructurePlacement spread)
        {
            int spacing = Math.max(1, spread.spacing());
            int minRegionX = Math.floorDiv(minChunkX, spacing);
            int maxRegionX = Math.floorDiv(maxChunkX, spacing);
            int minRegionZ = Math.floorDiv(minChunkZ, spacing);
            int maxRegionZ = Math.floorDiv(maxChunkZ, spacing);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++)
            {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++)
                {
                    ChunkPos candidate = spread.getPotentialStructureChunk(seed, regionX * spacing, regionZ * spacing);
                    if (chunkX(candidate) < minChunkX || chunkX(candidate) > maxChunkX
                            || chunkZ(candidate) < minChunkZ || chunkZ(candidate) > maxChunkZ)
                    {
                        continue;
                    }
                    // 与原版 StructureCheck#checkStart 里的第一步判断保持一致
                    if (!spread.applyAdditionalChunkRestrictions(chunkX(candidate), chunkZ(candidate), seed))
                    {
                        continue;
                    }
                    result.add(candidate);
                }
            }
        }
        else if (placement instanceof ConcentricRingsStructurePlacement rings)
        {
            List<ChunkPos> ringPositions = state.getRingPositionsFor(rings);
            if (ringPositions != null)
            {
                for (ChunkPos candidate : ringPositions)
                {
                    if (chunkX(candidate) < minChunkX || chunkX(candidate) > maxChunkX
                            || chunkZ(candidate) < minChunkZ || chunkZ(candidate) > maxChunkZ)
                    {
                        continue;
                    }
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private static double chunkCenterDistSqr(ChunkPos chunk, BlockPos center)
    {
        double dx = chunk.getMinBlockX() + 8.0 - center.getX();
        double dz = chunk.getMinBlockZ() + 8.0 - center.getZ();
        return dx * dx + dz * dz;
    }

    /** 中心点到区块方框最近一点的距离，用来做「不可能更近就不用再判了」的提前退出 */
    private static double chunkBoxDistSqr(ChunkPos chunk, BlockPos center)
    {
        double dx = Math.max(0.0, Math.max(chunk.getMinBlockX() - center.getX(),
                center.getX() - (chunk.getMinBlockX() + 15)));
        double dz = Math.max(0.0, Math.max(chunk.getMinBlockZ() - center.getZ(),
                center.getZ() - (chunk.getMinBlockZ() + 15)));
        return dx * dx + dz * dz;
    }

    // ======================================================================
    // 生物群系（并行取样）
    // ======================================================================

    private static int executeBiome(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ResourceOrTagArgument.Result<Biome> target = ResourceOrTagArgument.getResourceOrTag(
                context, "biome", Registries.BIOME);
        Options options = Options.of(context);
        BlockPos center = options.center() != null ? options.center() : BlockPos.containing(source.getPosition());
        announce(source, "生物群系 " + target.asPrintable());

        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        VisitIndex visits = new VisitIndex(level);
        if (options.radius() == null)
        {
            // 没写 radius：原版语义，只找最近的一个
            BlockPos pos;
            if ("any".equals(options.chunkFilter()))
            {
                Pair<BlockPos, Holder<Biome>> pair = level.findClosestBiome3d(
                        target, center, VANILLA_BIOME_SEARCH_RADIUS, 32, 64);
                pos = pair != null ? pair.getFirst() : null;
            }
            else
            {
                BiomeSamples samples = collectBiomeSamples(level, center, VANILLA_BIOME_SEARCH_RADIUS,
                        options.chunkFilter(), visits);
                pos = scanBiomes(level, target, center, samples).nearest();
            }
            stopwatch.stop();
            if (pos == null)
            {
                throw ERROR_BIOME_NOT_FOUND.create(target.asPrintable());
            }
            int result = showNearest(source, "commands.locate.biome.success", target.asPrintable(),
                    center, pos, true);
            feedback(source, Component.literal("（用时 " + millis(stopwatch) + " ms）").withStyle(ChatFormatting.DARK_GRAY));
            return result;
        }

        int radius = options.radius();
        BiomeSamples samples = collectBiomeSamples(level, center, radius, options.chunkFilter(), visits);
        BiomeScan scan = scanBiomes(level, target, center, samples);
        stopwatch.stop();
        long[] positions = samples.positions();
        double area = positions.length * 256.0;
        feedback(source, Component.literal(String.format(
                        "搜索完成：以 (%d, %d, %d) 为中心、半径 %d 格内，共扫描 %d 个区块，其中 %d 个区块属于 %s（约 %.2f 平方公里，用时 %d ms）",
                        center.getX(), center.getY(), center.getZ(), radius, positions.length, scan.matched(),
                        target.asPrintable(), area / 1_000_000.0, millis(stopwatch)))
                .withStyle(ChatFormatting.GREEN));
        if (positions.length > 0)
        {
            feedback(source, Component.literal(String.format("占比 %.1f%%", scan.matched() * 100.0 / positions.length))
                    .withStyle(ChatFormatting.GRAY));
        }
        feedback(source, Component.literal(String.format("其中 %d 个在", scan.matchedVisited()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("【已到访】").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.format("的区块里，%d 个在", scan.matched() - scan.matchedVisited()))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("【未到访】").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("的区块里").withStyle(ChatFormatting.GRAY)));
        if (scan.nearest() != null)
        {
            Component line = Component.literal("最近的一个在 ").withStyle(ChatFormatting.GRAY)
                    .append(coordinate(scan.nearest(), true))
                    .append(Component.literal("  "))
                    .append(teleportButton(scan.nearest(), false));
            feedback(source, line);
        }
        return Math.max(1, scan.matched());
    }

    /** 取样用的区块：positions 是区块中点打包坐标，visited[i] 表示这一格玩家到访过没有 */
    private record BiomeSamples(long[] positions, boolean[] visited)
    {
    }

    /** 按quart坐标取一个生物群系。各版本取 sampler / resolver 的写法不同，这里抹平 */
    private interface BiomeAt
    {
        Holder<Biome> get(int quartX, int quartY, int quartZ);
    }

    private static BiomeAt biomeSampler(ServerLevel level)
    {
        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        // 版本差异：26.3 起 BiomeSource 不再暴露 getNoiseBiome(..., sampler)，
        // 改成先拿一个 BiomeResolver（内部自己管取样上下文）再取生物群系。
        //? if >=26.3 {
        /*var resolver = biomeSource.createUncachedResolver(level.getChunkSource().randomState());
        return resolver::getNoiseBiome;*/
        //?} else {
        var sampler = level.getChunkSource().randomState().sampler();
        return (qx, qy, qz) -> biomeSource.getNoiseBiome(qx, qy, qz, sampler);
        //?}
    }

    /** 主线程把要取样的区块收集起来（顺便按「到访过没有」过滤），避免并行线程去读存档 */
    private static BiomeSamples collectBiomeSamples(ServerLevel level, BlockPos center, int radius,
                                                    String chunkFilter, VisitIndex visits)
            throws CommandSyntaxException
    {
        int chunkRadius = (radius >> 4) + 1;
        int minChunkX = (center.getX() >> 4) - chunkRadius;
        int maxChunkX = (center.getX() >> 4) + chunkRadius;
        int minChunkZ = (center.getZ() >> 4) - chunkRadius;
        int maxChunkZ = (center.getZ() >> 4) + chunkRadius;
        long radiusSqr = (long) radius * radius;
        LongList samples = new LongArrayList();
        LongList visitedFlags = new LongArrayList();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
        {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
            {
                double dx = chunkX * 16.0 + 8.0 - center.getX();
                double dz = chunkZ * 16.0 + 8.0 - center.getZ();
                if (dx * dx + dz * dz > radiusSqr)
                {
                    continue;
                }
                boolean visited = visits.visited(chunkX, chunkZ);
                if (!"any".equals(chunkFilter) && !("visited".equals(chunkFilter) == visited))
                {
                    continue;
                }
                samples.add(BlockPos.asLong(chunkX * 16 + 8, 0, chunkZ * 16 + 8));
                visitedFlags.add(visited ? 1L : 0L);
                if (samples.size() > MAX_BIOME_SAMPLES)
                {
                    throw ERROR_RADIUS_TOO_LARGE.create(samples.size());
                }
            }
        }
        long[] positionArray = samples.toLongArray();
        boolean[] visitedArray = new boolean[positionArray.length];
        for (int i = 0; i < visitedArray.length; i++)
        {
            visitedArray[i] = visitedFlags.getLong(i) != 0L;
        }
        return new BiomeSamples(positionArray, visitedArray);
    }

    private record BiomeScan(int matched, int matchedVisited, BlockPos nearest)
    {
    }

    /**
     * 并行取样。每一片顺手记下「本片里离中心最近的那个命中区块」，
     * 这样「范围内数量统计」和「找最近的一个」可以共用同一次扫描。
     */
    private static BiomeScan scanBiomes(ServerLevel level, ResourceOrTagArgument.Result<Biome> target,
                                        BlockPos center, BiomeSamples samples)
    {
        long[] positions = samples.positions();
        boolean[] visited = samples.visited();
        if (positions.length == 0)
        {
            return new BiomeScan(0, 0, null);
        }
        int y = center.getY();
        BiomeAt biomeAt = biomeSampler(level);
        int slices = Math.max(1, Math.min(8, positions.length / 2048));
        BetterLocate.LOGGER.info("并行取样生物群系：{} 个区块切成 {} 片并行计算", positions.length, slices);
        List<CompletableFuture<long[]>> futures = new ArrayList<>();
        for (int slice = 0; slice < slices; slice++)
        {
            int from = (int) ((long) positions.length * slice / slices);
            int to = (int) ((long) positions.length * (slice + 1) / slices);
            futures.add(CompletableFuture.supplyAsync(() -> {
                int hits = 0;
                int hitsVisited = 0;
                int nearestIndex = -1;
                double nearestDist = Double.MAX_VALUE;
                for (int i = from; i < to; i++)
                {
                    long key = positions[i];
                    Holder<Biome> biome = biomeAt.get(
                            QuartPos.fromBlock(BlockPos.getX(key)),
                            QuartPos.fromBlock(y),
                            QuartPos.fromBlock(BlockPos.getZ(key)));
                    if (target.test(biome))
                    {
                        hits++;
                        if (visited[i])
                        {
                            hitsVisited++;
                        }
                        double dx = BlockPos.getX(key) - center.getX();
                        double dz = BlockPos.getZ(key) - center.getZ();
                        double dist = dx * dx + dz * dz;
                        if (dist < nearestDist)
                        {
                            nearestDist = dist;
                            nearestIndex = i;
                        }
                    }
                }
                return new long[]{hits, nearestIndex, hitsVisited};
            }, POOL));
        }
        int matched = 0;
        int matchedVisited = 0;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (CompletableFuture<long[]> future : futures)
        {
            long[] part = future.join();
            matched += (int) part[0];
            matchedVisited += (int) part[2];
            int index = (int) part[1];
            if (index >= 0)
            {
                long key = positions[index];
                double dx = BlockPos.getX(key) - center.getX();
                double dz = BlockPos.getZ(key) - center.getZ();
                double dist = dx * dx + dz * dz;
                if (dist < nearestDist)
                {
                    nearestDist = dist;
                    nearest = new BlockPos(BlockPos.getX(key), y, BlockPos.getZ(key));
                }
            }
        }
        return new BiomeScan(matched, matchedVisited, nearest);
    }

    // ======================================================================
    // 兴趣点
    // ======================================================================

    private static int executePoi(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ResourceOrTagArgument.Result<PoiType> target = ResourceOrTagArgument.getResourceOrTag(
                context, "poi", Registries.POINT_OF_INTEREST_TYPE);
        Options options = Options.of(context);
        BlockPos center = options.center() != null ? options.center() : BlockPos.containing(source.getPosition());
        announce(source, "兴趣点 " + target.asPrintable());

        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        VisitIndex visits = new VisitIndex(level);
        // 没写 radius 就按原版的搜索范围（256 格）找最近的一个；写了就统计整个半径
        int radius = options.radius() != null ? options.radius() : VANILLA_POI_SEARCH_RADIUS;
        List<BlockPos> found = level.getPoiManager()
                .findAll(holder -> target.test(holder), pos -> true, center, radius, PoiManager.Occupancy.ANY)
                .filter(pos -> chunkAllowed(visits, pos.getX() >> 4, pos.getZ() >> 4, options.chunkFilter()))
                .sorted(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)))
                .limit(MAX_POI_RESULTS)
                .toList();
        stopwatch.stop();

        if (options.radius() == null)
        {
            if (found.isEmpty())
            {
                throw ERROR_POI_NOT_FOUND.create(target.asPrintable());
            }
            BlockPos pos = found.get(0);
            int result = showNearest(source, "commands.locate.poi.success", target.asPrintable(),
                    center, pos, true);
            feedback(source, Component.literal("（用时 " + millis(stopwatch) + " ms）").withStyle(ChatFormatting.DARK_GRAY));
            return result;
        }
        printStats(source, "兴趣点", target.asPrintable(), center, radius, found, stopwatch, false, visits);
        return Math.max(1, found.size());
    }

    // ======================================================================
    // 统计输出
    // ======================================================================

    private static void printStats(CommandSourceStack source, String kind, String name, BlockPos center,
                                   int radius, List<BlockPos> found, Stopwatch stopwatch, boolean keepY,
                                   VisitIndex visits)
    {
        feedback(source, Component.literal(String.format(
                        "搜索完成：以 (%d, %d, %d) 为中心、半径 %d 格内共找到 %d 个%s %s（用时 %d ms）",
                        center.getX(), center.getY(), center.getZ(), radius, found.size(), kind, name,
                        millis(stopwatch)))
                .withStyle(ChatFormatting.GREEN));
        if (found.isEmpty())
        {
            return;
        }
        int visitedCount = 0;
        for (BlockPos pos : found)
        {
            if (visits.visited(pos.getX() >> 4, pos.getZ() >> 4))
            {
                visitedCount++;
            }
        }
        feedback(source, Component.literal(String.format("其中 %d 个在", visitedCount))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("【已到访】").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.format("的区块里，%d 个在", found.size() - visitedCount))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("【未到访】").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("的区块里").withStyle(ChatFormatting.GRAY)));
        int listed = 0;
        for (BlockPos pos : found)
        {
            if (listed >= MAX_LISTED)
            {
                feedback(source, Component.literal("…… 还有 " + (found.size() - MAX_LISTED) + " 个未列出")
                        .withStyle(ChatFormatting.GRAY));
                break;
            }
            listed++;
            String y = keepY ? "~" : String.valueOf(pos.getY());
            Component line = Component.literal(String.format("  #%d  (%d, %s, %d)  距离 %.1f 格  ",
                            listed, pos.getX(), y, pos.getZ(), Math.sqrt(pos.distSqr(center))))
                    .withStyle(ChatFormatting.GRAY)
                    .append(visitTag(visits, pos))
                    .append(Component.literal("  "))
                    .append(teleportButton(pos, keepY));
            feedback(source, line);
        }
    }
}

