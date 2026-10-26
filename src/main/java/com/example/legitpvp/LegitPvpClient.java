package com.example.legitpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 正規(legit)PvPクライアント。表示と利便性のみ。
 * リーチ延長・オートクリッカー・ヒットボックス拡大などの戦闘補助は含まない。
 */
public class LegitPvpClient implements ClientModInitializer {
    private static final Deque<Long> leftClicks = new ArrayDeque<>();
    private static final Deque<Long> rightClicks = new ArrayDeque<>();
    private static boolean lastLeft, lastRight;

    private static boolean hudEnabled = true;
    private static boolean toggleSprint = true;
    private static KeyBinding toggleHudKey, toggleSprintKey;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("legitpvp", "main"));
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.legitpvp.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        toggleSprintKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.legitpvp.toggle_sprint", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleHudKey.wasPressed()) hudEnabled = !hudEnabled;
            while (toggleSprintKey.wasPressed()) toggleSprint = !toggleSprint;

            // トグルスプリント: 前進中にダッシュを自動で入れるだけ(速度は通常のダッシュと同じ)
            if (toggleSprint && client.player != null && client.options.forwardKey.isPressed()
                    && !client.player.isUsingItem() && !client.player.horizontalCollision) {
                client.player.setSprinting(true);
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("legitpvp", "hud"), LegitPvpClient::render);
    }

    private static void pollClicks(MinecraftClient mc) {
        long handle = mc.getWindow().getHandle();
        boolean l = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean r = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();
        if (l && !lastLeft) leftClicks.add(now);
        if (r && !lastRight) rightClicks.add(now);
        lastLeft = l;
        lastRight = r;
        while (!leftClicks.isEmpty() && now - leftClicks.peek() > 1000) leftClicks.poll();
        while (!rightClicks.isEmpty() && now - rightClicks.peek() > 1000) rightClicks.poll();
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        pollClicks(mc);
        if (!hudEnabled || mc.options.hudHidden) return;

        int x = 6, y = 6;
        ctx.drawTextWithShadow(mc.textRenderer, "FPS: " + mc.getCurrentFps(), x, y, 0xFFFFFFFF);
        y += 11;
        ctx.drawTextWithShadow(mc.textRenderer, "CPS: " + leftClicks.size() + " | " + rightClicks.size(), x, y, 0xFFFFFFFF);
        y += 11;
        var handler = mc.getNetworkHandler();
        var entry = handler == null ? null : handler.getPlayerListEntry(mc.player.getUuid());
        if (entry != null) {
            ctx.drawTextWithShadow(mc.textRenderer, "Ping: " + entry.getLatency() + "ms", x, y, 0xFFFFFFFF);
            y += 11;
        }
        ctx.drawTextWithShadow(mc.textRenderer, "Sprint: " + (toggleSprint ? "ON" : "OFF"), x, y,
                toggleSprint ? 0xFF55FF55 : 0xFFFF5555);

        drawKeystrokes(ctx, mc, x, y + 16);
        drawArmor(ctx, mc);
    }

    private static void drawKeystrokes(DrawContext ctx, MinecraftClient mc, int x, int y) {
        int s = 20, g = 2;
        key(ctx, mc, "W", mc.options.forwardKey.isPressed(), x + s + g, y, s);
        key(ctx, mc, "A", mc.options.leftKey.isPressed(), x, y + s + g, s);
        key(ctx, mc, "S", mc.options.backKey.isPressed(), x + s + g, y + s + g, s);
        key(ctx, mc, "D", mc.options.rightKey.isPressed(), x + 2 * (s + g), y + s + g, s);
        int half = (s * 3 + g * 2) / 2 - 1;
        key(ctx, mc, "LMB", lastLeft, x, y + 2 * (s + g), half);
        key(ctx, mc, "RMB", lastRight, x + half + 2, y + 2 * (s + g), half);
    }

    private static void key(DrawContext ctx, MinecraftClient mc, String label, boolean down, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + 20, down ? 0xAAFFFFFF : 0x80000000);
        int tw = mc.textRenderer.getWidth(label);
        ctx.drawText(mc.textRenderer, label, x + (w - tw) / 2, y + 6, down ? 0xFF000000 : 0xFFFFFFFF, false);
    }

    private static void drawArmor(DrawContext ctx, MinecraftClient mc) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND};
        int x = ctx.getScaledWindowWidth() - 60;
        int y = ctx.getScaledWindowHeight() / 2 - 50;
        for (EquipmentSlot slot : slots) {
            ItemStack st = mc.player.getEquippedStack(slot);
            if (!st.isEmpty()) {
                ctx.drawItem(st, x, y);
                if (st.isDamageable()) {
                    ctx.drawTextWithShadow(mc.textRenderer, String.valueOf(st.getMaxDamage() - st.getDamage()),
                            x + 20, y + 4, 0xFFFFFFFF);
                }
            }
            y += 20;
        }
    }
}
