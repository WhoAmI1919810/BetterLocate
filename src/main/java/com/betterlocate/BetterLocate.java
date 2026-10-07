package com.betterlocate;

import com.betterlocate.command.SafeTpCommand;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * BetterLocate —— 优化原版 {@code /locate}，并额外提供一个 {@code /safetp} 安全传送指令。
 *
 * <p>{@code /locate} 用 Mixin 拦住原版 {@code LocateCommand#register} 的注册，
 * 换成我们这版（见 {@code com.betterlocate.command.BetterLocateCommand}）；
 * {@code /safetp} 走 NeoForge 的 {@link RegisterCommandsEvent} 注册。
 */
@Mod(BetterLocate.MODID)
public class BetterLocate
{
    public static final String MODID = "betterlocate";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterLocate()
    {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                SafeTpCommand.register(event.getDispatcher(), event.getBuildContext()));
        LOGGER.info("BetterLocate 已加载：/locate 换成增强版，新增 /safetp 安全传送");
    }
}