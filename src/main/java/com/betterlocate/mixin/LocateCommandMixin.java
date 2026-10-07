package com.betterlocate.mixin;

import com.betterlocate.command.BetterLocateCommand;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.LocateCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把原版 {@code /locate} 的注册整个换成增强版。
 *
 * <p>不新增指令，也不去改 Brigadier 的节点树（那样容易和原版节点合并出错），
 * 直接在注册入口处取消原版注册、注册我们这一份。
 */
@Mixin(LocateCommand.class)
public class LocateCommandMixin
{
    @Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private static void betterlocate$replace(CommandDispatcher<CommandSourceStack> dispatcher,
                                             CommandBuildContext context,
                                             CallbackInfo callback)
    {
        BetterLocateCommand.register(dispatcher, context);
        callback.cancel();
    }
}
