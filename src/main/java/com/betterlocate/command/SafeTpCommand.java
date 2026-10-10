package com.betterlocate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /safetp}：把实体传送到目标 X/Z 附近「能站得住」的位置。
 *
 * <pre>
 * /safetp [targets] [&lt;x z&gt;] [top|bottom] [next] [normal|force] [radius]
 * /safetp next
 * </pre>
 *
 * <ul>
 *   <li>targets：缺省为执行者自己；x/z 缺省为该实体执行指令时所在的位置；</li>
 *   <li>top/bottom：从世界顶端往下找 / 从底端往上找。缺省时沿用该实体上一次的选择，
 *       没有记录则用 top；</li>
 *   <li>水面：用 top 从空中往下扫描时，若一路都是空气、第一个遇到的非空气方块
 *       是水（流体），就直接落在水面上——海洋、河流、湖泊等任何含水面的地方
 *       都适用，之后可用 next 继续向下找真正站得住的安全位置；若水面之上
 *       先有别的非空气方块（树冠、桥、房屋……）则水面不生效，按普通规则找；</li>
 *   <li>next：不重新搜整根柱子，而是从上一次找到的安全位置（或脚下已经算安全的
 *       位置）出发，沿当前方向找下一个。top 的「下一个」在下方，bottom 的在上方。
 *       next 只找真正的安全位置，不会落在水里；</li>
 *   <li>normal：每个候选落脚点先在其 radius 格垂直范围内就近换到满足「安全传送点」的
 *       位置，换不到就继续找下一个候选；</li>
 *   <li>force：找到第一个满足「强制传送点」的候选后同样先尝试就近换成安全位置，
 *       换不到就直接落在该候选上，不再继续找；</li>
 *   <li>radius：就近替换的搜索半径，默认 3。</li>
 * </ul>
 *
 * <p>安全传送点：脚下是不会塌陷、不造成伤害的实体方块，腿部与头部两格没有碰撞、
 * 没有流体、没有细雪、没有会伤害玩家的方块，也不会通向虚空。</p>
 *
 * <p>强制传送点：只要求脚下是能站人的实体方块，腿部与头部两格不是实体方块
 * （允许是空气、流体、细雪等非实体方块）。</p>
 *
 * <p>水面点：从空中一路往下第一个非空气方块是流体时，其上的空气格。
 * 只在上方向下找（top）的初次落地时生效；先碰到其他非空气方块，或 next
 * 继续找时都不生效。</p>
 */
public final class SafeTpCommand
{
    private static final int DEFAULT_RADIUS = 3;
    private static final int MAX_RADIUS = 64;
    /** 「已经算站在这个安全位置上」的判定距离 */
    private static final int NEAR_DISTANCE = 4;

    private static final SimpleCommandExceptionType ERROR_NO_SAFE =
            new SimpleCommandExceptionType(Component.literal("在该纵列上没有找到可用的传送点"));
    private static final SimpleCommandExceptionType ERROR_NO_LAST =
            new SimpleCommandExceptionType(Component.literal("没有记录上一个安全位置，无法使用 next"));

    /** 每个实体记住：上一次选了 top 还是 bottom、上一次落到的安全位置 */
    private static final Map<UUID, SearchState> STATES = new HashMap<>();

    private record SearchState(boolean bottom, BlockPos lastPos)
    {
    }

    private SafeTpCommand()
    {
    }

    // ======================================================================
    // 指令树
    // ======================================================================

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("safetp");
        // 版本差异：1.21.11 起权限改成 PermissionSet
        //? if >=1.21.11 {
        /*root.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));*/
        //?} else {
        root.requires(source -> source.hasPermission(2));
        //?}

        // /safetp 裸指令 —— 等价于 /safetp next
        root.executes(ctx -> execute(ctx, null, true, false, DEFAULT_RADIUS));
        root.then(Commands.literal("next").executes(ctx -> execute(ctx, null, true, false, DEFAULT_RADIUS)));

        ArgumentBuilder<CommandSourceStack, ?> targets = Commands.argument("targets", EntityArgument.entities());
        targets.executes(ctx -> execute(ctx, null, false, false, DEFAULT_RADIUS));
        attachCoordinates(targets);
        root.then(targets);

        // 省略目标选择器时，坐标直接跟在 /safetp 后面。
        attachCoordinates(root);

        dispatcher.register(root);
    }

    private static void attachCoordinates(ArgumentBuilder<CommandSourceStack, ?> node)
    {
        ArgumentBuilder<CommandSourceStack, ?> pos = Commands.argument("pos", BlockPosArgument.blockPos());
        pos.executes(ctx -> execute(ctx, null, false, false, DEFAULT_RADIUS));
        attachTail(pos);
        node.then(pos);
    }

    /** 把 [top|bottom]、[next]、[normal|force]、[radius] 这串可选尾巴挂到节点下 */
    private static void attachTail(ArgumentBuilder<CommandSourceStack, ?> node)
    {
        for (boolean bottom : new boolean[]{false, true})
        {
            LiteralArgumentBuilder<CommandSourceStack> dir = Commands.literal(bottom ? "bottom" : "top");
            dir.executes(ctx -> execute(ctx, !bottom, false, false, DEFAULT_RADIUS));
            attachModeAndRadius(dir, !bottom, false);
            // 方向之后的 next：沿该方向继续找
            LiteralArgumentBuilder<CommandSourceStack> next = Commands.literal("next");
            next.executes(ctx -> execute(ctx, !bottom, true, false, DEFAULT_RADIUS));
            attachModeAndRadius(next, !bottom, true);
            dir.then(next);
            node.then(dir);
        }
        // 不写方向也允许直接 next：方向沿用上一次的选择
        LiteralArgumentBuilder<CommandSourceStack> next = Commands.literal("next");
        next.executes(ctx -> execute(ctx, null, true, false, DEFAULT_RADIUS));
        attachModeAndRadius(next, null, true);
        node.then(next);
    }

    /** 挂 [normal|force]，各自还能再跟一个 [radius] */
    private static void attachModeAndRadius(ArgumentBuilder<CommandSourceStack, ?> node, Boolean top, boolean next)
    {
        for (boolean force : new boolean[]{false, true})
        {
            LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal(force ? "force" : "normal");
            mode.executes(ctx -> execute(ctx, top, next, force, DEFAULT_RADIUS));
            ArgumentBuilder<CommandSourceStack, ?> radius =
                    Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS));
            radius.executes(ctx -> execute(ctx, top, next, force,
                    IntegerArgumentType.getInteger(ctx, "radius")));
            mode.then(radius);
            node.then(mode);
        }
    }

    // ======================================================================
    // 执行
    // ======================================================================

    private static int execute(CommandContext<CommandSourceStack> context, Boolean top,
                               boolean next, boolean force, int radius) throws CommandSyntaxException
    {
        CommandSourceStack source = context.getSource();
        Collection<? extends Entity> entities = hasNode(context, "targets")
                ? EntityArgument.getEntities(context, "targets")
                : List.of(source.getEntityOrException());
        int teleported = 0;
        for (Entity entity : entities)
        {
            if (!(entity.level() instanceof ServerLevel level))
            {
                continue;
            }
            SearchState state = STATES.get(entity.getUUID());
            boolean bottom = top != null ? !top : (state != null && state.bottom());
            // 水面落地只在初次传送时生效：next 永远找真正的安全位置，不落在水里
            boolean waterMode = !next;
            BlockPos requested = hasNode(context, "pos")
                    ? BlockPosArgument.getBlockPos(context, "pos")
                    : BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
            int x = requested.getX();
            int z = requested.getZ();

            BlockPos target;
            if (next)
            {
                // next：从上一次的安全位置接着找；没有记录但脚下已经算安全位置时，从脚下接着找
                int startY;
                if (state != null && state.lastPos() != null)
                {
                    startY = state.lastPos().getY();
                }
                else
                {
                    BlockPos near = nearSafeSpot(level, entity, x, z, bottom);
                    if (near == null)
                    {
                        throw ERROR_NO_LAST.create();
                    }
                    startY = near.getY();
                }
                target = findSpot(level, x, z, bottom, force, waterMode, radius,
                        startY + (bottom ? 1 : -1));
            }
            else
            {
                // 如果已经算站在某个安全位置附近，直接落过去，不再整列扫描
                BlockPos near = nearSafeSpot(level, entity, x, z, bottom);
                target = near != null ? near
                        : findSpot(level, x, z, bottom, force, waterMode, radius,
                                bottom ? minY(level) + 1 : maxY(level) - 1);
            }

            if (target == null)
            {
                throw ERROR_NO_SAFE.create();
            }
            // 版本差异：1.21.2 起 teleportTo 多了 setCamera 参数
            //? if >=1.21.2 {
            /*entity.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    Set.of(), entity.getYRot(), entity.getXRot(), false);*/
            //?} else {
            entity.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    Set.of(), entity.getYRot(), entity.getXRot());
            //?}
            STATES.put(entity.getUUID(), new SearchState(bottom, target));
            teleported++;
            String nextLabel = bottom ? "继续向上" : "继续向下";
            
            source.sendSuccess(() -> Component.literal(String.format(
                            "已将 %s 安全传送到 (%d, %d, %d)  ",
                            entity.getName().getString(), target.getX(), target.getY(), target.getZ()))
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("[" + nextLabel + "]")
                            .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                    .withClickEvent(nextClick())
                                    .withHoverEvent(showText(Component.literal(nextLabel + "：执行 /safetp next"))))), false);
        }
        return teleported;
    }

    private static boolean hasNode(CommandContext<?> context, String name)
    {
        return context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals(name));
    }

    private static ClickEvent nextClick()
    {
        // 版本差异：1.21.5 起 ClickEvent 由类变成接口
        //? if >=1.21.5 {
        /*return new ClickEvent.RunCommand("/safetp next");*/
        //?} else {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/safetp next");
        //?}
    }

    private static HoverEvent showText(Component text)
    {
        //? if >=1.21.5 {
        /*return new HoverEvent.ShowText(text);*/
        //?} else {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        //?}
    }

    // ======================================================================
    // 寻找传送点
    // ======================================================================

    /**
     * 按 X/Z 距离逐圈检查列；每列沿方向逐个检查「强制传送点」。normal 只接受安全点，
     * force 找到强制点后允许回退到该点。
     *
     * @param fromY 扫描起点（脚所在格），会夹到世界的可用范围内
     */
    private static BlockPos findSpot(ServerLevel level, int x, int z, boolean bottom,
                                     boolean force, boolean water, int radius, int fromY)
    {
        // radius 表示 X/Z 平面的搜索半径：按 0、1、2... 的方形外圈逐圈检查。
        for (int distance = 0; distance <= radius; distance++)
        {
            for (int[] offset : ring(distance))
            {
                int columnX = x + offset[0];
                int columnZ = z + offset[1];
                BlockPos candidate = findSpotInColumn(level, columnX, columnZ, bottom, force, water, fromY);
                if (candidate != null)
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static BlockPos findSpotInColumn(ServerLevel level, int x, int z, boolean bottom,
                                             boolean force, boolean water, int fromY)
    {
        int lo = minY(level) + 1;
        int hi = maxY(level) - 1;
        int y = Math.max(lo, Math.min(hi, fromY));
        int step = bottom ? 1 : -1;
        // 水面规则：top 向下扫时，「一路空气、第一个非空气方块是流体」才允许落水面。
        // 先在纵列上碰到任何其他非空气方块（树叶、建筑……），水面就不再算数。
        // 从 fromY 往下扫时，fromY 之上的情况并不知道，但只要脚下是水面就同样适用。
        boolean skyClear = water && !bottom;
        while (y >= lo && y <= hi)
        {
            BlockPos candidate = new BlockPos(x, y, z);
            if (skyClear && isWaterSurface(level, candidate))
            {
                return candidate;
            }
            if (isForceSpot(level, candidate))
            {
                if (isSafeSpot(level, candidate))
                {
                    return candidate;
                }
                if (force)
                {
                    return candidate;
                }
            }
            // 跟踪「水面规则是否仍然成立」：遇到非空气、非流体的方块后就失效
            if (skyClear)
            {
                BlockState state = level.getBlockState(candidate);
                if (!state.isAir() && state.getFluidState().isEmpty())
                {
                    skyClear = false;
                }
            }
            y += step;
        }
        return null;
    }

    /** 返回方形第 distance 圈，圈内顺序固定，distance=0 只有中心列。 */
    private static List<int[]> ring(int distance)
    {
        if (distance == 0)
        {
            return List.of(new int[]{0, 0});
        }
        List<int[]> result = new java.util.ArrayList<>(distance * 8);
        for (int dx = -distance; dx <= distance; dx++)
        {
            result.add(new int[]{dx, -distance});
            result.add(new int[]{dx, distance});
        }
        for (int dz = -distance + 1; dz <= distance - 1; dz++)
        {
            result.add(new int[]{-distance, dz});
            result.add(new int[]{distance, dz});
        }
        return result;
    }

    /**
     * 「玩家其实已经站在某个安全位置附近」的判定：纵列不变，在当前脚下方格往上/下
     * NEAR_DISTANCE 格以内找一个安全位置，且玩家与该位置之间没有实体方块挡路。
     * top 往脚下找，bottom 往头上找——和各自的扫描方向一致。
     */
    private static BlockPos nearSafeSpot(ServerLevel level, Entity entity, int x, int z, boolean bottom)
    {
        int feetY = Mth.floor(entity.getY());
        int lo = minY(level) + 1;
        int hi = maxY(level) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= NEAR_DISTANCE; d++)
        {
            int sy = feetY + (bottom ? d : -d);
            if (sy < lo || sy > hi)
            {
                break;
            }
            pos.set(x, sy, z);
            if (!isSafeSpot(level, pos))
            {
                continue;
            }
            // 玩家与该位置之间的方块不能有实体方块挡路
            boolean clear = true;
            for (int between = 1; between < d; between++)
            {
                pos.set(x, feetY + (bottom ? between : -between), z);
                if (solidAt(level, pos))
                {
                    clear = false;
                    break;
                }
            }
            if (clear)
            {
                return pos.immutable();
            }
        }
        return null;
    }

    // ======================================================================
    // 版本差异：世界高度范围在 1.21.2 起改名
    // ======================================================================

    private static int minY(ServerLevel level)
    {
        //? if >=1.21.2 {
        /*return level.getMinY();*/
        //?} else {
        return level.getMinBuildHeight();
        //?}
    }

    private static int maxY(ServerLevel level)
    {
        //? if >=1.21.2 {
        /*return level.getMaxY();*/
        //?} else {
        return level.getMaxBuildHeight() - 1;
        //?}
    }
    // ======================================================================
    // 方块判定
    // ======================================================================

    /** 强制传送点：脚下能站人，腿部与头部两格都不是实体方块 */
    private static boolean isForceSpot(ServerLevel level, BlockPos pos)
    {
        return standable(level, pos.below())
                && !solidAt(level, pos)
                && !solidAt(level, pos.above());
    }

    /** 安全传送点：强制传送点的基础上，再加全套安全要求 */
    private static boolean isSafeSpot(ServerLevel level, BlockPos pos)
    {
        BlockPos groundPos = pos.below();
        BlockState ground = level.getBlockState(groundPos);
        if (!standable(level, groundPos, ground)
                || ground.getBlock() instanceof FallingBlock   // 沙子/沙砾这类会塌陷
                || hurts(ground)                                // 岩浆块/仙人掌这类站上去会受伤
                || !ground.getFluidState().isEmpty())
        {
            return false;
        }
        return spaceSafe(level, pos) && spaceSafe(level, pos.above());
    }

    /** 身体一格：没有碰撞、没有流体、没有细雪、没有会伤害玩家的方块 */
    private static boolean spaceSafe(ServerLevel level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof PowderSnowBlock)
                && !hurts(state);
    }

    /** 水面点（onwater 用）：本格与头顶安全、脚下是流体（海水等） */
    private static boolean isWaterSurface(ServerLevel level, BlockPos pos)
    {
        return spaceSafe(level, pos)
                && spaceSafe(level, pos.above())
                && !level.getBlockState(pos.below()).getFluidState().isEmpty();
    }

    private static boolean standable(BlockGetter level, BlockPos pos)
    {
        return standable(level, pos, level.getBlockState(pos));
    }

    /** 这一格能不能作为站立面：实体方块或顶面完整（台阶上半、泥土小径等） */
    private static boolean standable(BlockGetter level, BlockPos pos, BlockState state)
    {
        return solid(level, pos, state)
                || state.isFaceSturdy(level, pos, Direction.UP)
                || state.canOcclude();
    }

    private static boolean solidAt(ServerLevel level, BlockPos pos)
    {
        return solid(level, pos, level.getBlockState(pos));
    }

    /** 是否实体方块：1.21.2 起 isSolidRender 不再需要 level/pos 参数 */
    private static boolean solid(BlockGetter level, BlockPos pos, BlockState state)
    {
        //? if >=1.21.2 {
        /*return state.isSolidRender();*/
        //?} else {
        return state.isSolidRender(level, pos);
        //?}
    }

    /** 会不会对站在里面/上面的玩家造成伤害 */
    private static boolean hurts(BlockState state)
    {
        Block block = state.getBlock();
        return block instanceof FireBlock
                || block instanceof MagmaBlock
                || block instanceof CactusBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof WitherRoseBlock
                || block instanceof PowderSnowBlock
                || block instanceof CampfireBlock;
    }
}
