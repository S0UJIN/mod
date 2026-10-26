package com.example.legitpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

/**
 * 正規(legit)PvPクライアント。表示と利便性のみ。
 * リーチ延長・オートクリッカー・ヒットボックス拡大などの戦闘補助は含まない。
 */
public class LegitPvpClient implements ClientModInitializer {
    static final Deque<Long> leftClicks = new ArrayDeque<>();
    static final Deque<Long> rightClicks = new ArrayDeque<>();
    static boolean lastLeft, lastRight;
    static boolean toggleSprint = true;

    private static KeyBinding openMenuKey, toggleSprintKey;

    @Override
    public void onInitializeClient() {
        HudConfig.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("legitpvp", "main"));
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.legitpvp.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        toggleSprintKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.legitpvp.toggle_sprint", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    client.setScreen(new EditorScreen());
                }
            }
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

    static void pollClicks(MinecraftClient mc) {
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
        if (mc.player == null || mc.options.hudHidden) return;
        if (mc.currentScreen instanceof EditorScreen) return; // 編集中は編集画面側が描く
        pollClicks(mc);

        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        for (HudConfig.Element e : HudConfig.Element.values()) {
            if (e.enabled) HudRenderer.draw(ctx, mc, e, e.clampX(sw), e.clampY(sh));
        }
    }
}

/** 各HUD要素の ON/OFF と位置の保存・読み込み */
class HudConfig {
    enum Element {
        // 名前, 初期X, 初期Y, 幅, 高さ  (ARMORのXは大きい値 → 画面右端に自動で寄る)
        FPS("FPS", 6, 6, 64, 10),
        CPS("CPS", 6, 18, 64, 10),
        PING("Ping", 6, 30, 74, 10),
        SPRINT("スプリント表示", 6, 42, 70, 10),
        KEYSTROKES("キーストローク", 6, 60, 64, 64),
        ARMOR("アーマー耐久", 10000, 90, 46, 100);

        final String label;
        final int defX, defY, w, h;
        boolean enabled = true;
        int x, y;

        Element(String label, int defX, int defY, int w, int h) {
            this.label = label;
            this.defX = defX;
            this.defY = defY;
            this.w = w;
            this.h = h;
            this.x = defX;
            this.y = defY;
        }

        int clampX(int sw) { return Math.max(0, Math.min(x, sw - w)); }
        int clampY(int sh) { return Math.max(0, Math.min(y, sh - h)); }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("legitpvp.properties");
    }

    static void load() {
        Properties p = new Properties();
        try {
            if (Files.exists(file())) {
                try (InputStream in = Files.newInputStream(file())) {
                    p.load(in);
                }
            }
        } catch (Exception ignored) {
        }
        for (Element e : Element.values()) {
            e.enabled = Boolean.parseBoolean(p.getProperty(e.name() + ".enabled", "true"));
            e.x = parse(p.getProperty(e.name() + ".x"), e.defX);
            e.y = parse(p.getProperty(e.name() + ".y"), e.defY);
        }
    }

    static void save() {
        Properties p = new Properties();
        for (Element e : Element.values()) {
            p.setProperty(e.name() + ".enabled", String.valueOf(e.enabled));
            p.setProperty(e.name() + ".x", String.valueOf(e.x));
            p.setProperty(e.name() + ".y", String.valueOf(e.y));
        }
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Legit PvP Client HUD settings");
        } catch (Exception ignored) {
        }
    }

    static void reset() {
        for (Element e : Element.values()) {
            e.enabled = true;
            e.x = e.defX;
            e.y = e.defY;
        }
        save();
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}

/** 各HUD要素の描画 (ゲーム中と編集画面で共通) */
class HudRenderer {
    static void draw(DrawContext ctx, MinecraftClient mc, HudConfig.Element e, int x, int y) {
        switch (e) {
            case FPS -> ctx.drawTextWithShadow(mc.textRenderer, "FPS: " + mc.getCurrentFps(), x, y, 0xFFFFFFFF);
            case CPS -> ctx.drawTextWithShadow(mc.textRenderer,
                    "CPS: " + LegitPvpClient.leftClicks.size() + " | " + LegitPvpClient.rightClicks.size(),
                    x, y, 0xFFFFFFFF);
            case PING -> {
                String ping = "-";
                if (mc.player != null && mc.getNetworkHandler() != null) {
                    var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                    if (entry != null) ping = entry.getLatency() + "ms";
                }
                ctx.drawTextWithShadow(mc.textRenderer, "Ping: " + ping, x, y, 0xFFFFFFFF);
            }
            case SPRINT -> ctx.drawTextWithShadow(mc.textRenderer,
                    "Sprint: " + (LegitPvpClient.toggleSprint ? "ON" : "OFF"), x, y,
                    LegitPvpClient.toggleSprint ? 0xFF55FF55 : 0xFFFF5555);
            case KEYSTROKES -> drawKeystrokes(ctx, mc, x, y);
            case ARMOR -> drawArmor(ctx, mc, x, y);
        }
    }

    private static void drawKeystrokes(DrawContext ctx, MinecraftClient mc, int x, int y) {
        int s = 20, g = 2;
        key(ctx, mc, "W", mc.options.forwardKey.isPressed(), x + s + g, y, s);
        key(ctx, mc, "A", mc.options.leftKey.isPressed(), x, y + s + g, s);
        key(ctx, mc, "S", mc.options.backKey.isPressed(), x + s + g, y + s + g, s);
        key(ctx, mc, "D", mc.options.rightKey.isPressed(), x + 2 * (s + g), y + s + g, s);
        int half = (s * 3 + g * 2) / 2 - 1;
        key(ctx, mc, "LMB", LegitPvpClient.lastLeft, x, y + 2 * (s + g), half);
        key(ctx, mc, "RMB", LegitPvpClient.lastRight, x + half + 2, y + 2 * (s + g), half);
    }

    private static void key(DrawContext ctx, MinecraftClient mc, String label, boolean down, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + 20, down ? 0xAAFFFFFF : 0x80000000);
        int tw = mc.textRenderer.getWidth(label);
        ctx.drawText(mc.textRenderer, label, x + (w - tw) / 2, y + 6, down ? 0xFF000000 : 0xFFFFFFFF, false);
    }

    private static void drawArmor(DrawContext ctx, MinecraftClient mc, int x, int y) {
        if (mc.player == null) return;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND};
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

/** 設定メニュー: 項目のON/OFFと、ドラッグでの位置移動 */
class EditorScreen extends Screen {
    private static final int PW = 190, ROW = 16;

    private HudConfig.Element dragging;
    private int offX, offY;
    private boolean prevDown = true; // 開いた瞬間の押しっぱなしでクリック扱いにならないように

    EditorScreen() {
        super(Text.literal("HUD Editor"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        HudConfig.save();
        super.close();
    }

    private int panelH() {
        return 34 + HudConfig.Element.values().length * ROW + 30;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void box(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HudConfig.Element[] els = HudConfig.Element.values();

        // マウス状態を直接読む (ドラッグ処理用)
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == GLFW.GLFW_PRESS;
        if (down && !prevDown) onPress(mouseX, mouseY);
        if (down && dragging != null) {
            dragging.x = Math.max(0, Math.min(mouseX - offX, width - dragging.w));
            dragging.y = Math.max(0, Math.min(mouseY - offY, height - dragging.h));
        }
        if (!down && prevDown && dragging != null) {
            dragging = null;
            HudConfig.save();
        }
        prevDown = down;

        // 背景を少し暗く
        ctx.fill(0, 0, width, height, 0x55000000);

        // HUD要素のプレビュー(掴んで動かせる)
        for (HudConfig.Element e : els) {
            int ex = e.clampX(width), ey = e.clampY(height);
            HudRenderer.draw(ctx, mc, e, ex, ey);
            if (!e.enabled) ctx.fill(ex, ey, ex + e.w, ey + e.h, 0xAA000000);
            boolean hover = inside(mouseX, mouseY, ex, ey, e.w, e.h);
            int c = e == dragging ? 0xFFFFFF55 : hover ? 0xFFFFFFFF : e.enabled ? 0xFF55FF55 : 0xFF777777;
            box(ctx, ex - 1, ey - 1, e.w + 2, e.h + 2, c);
        }

        // 中央の設定パネル
        int ph = panelH();
        int px = (width - PW) / 2, py = (height - ph) / 2;
        ctx.fill(px, py, px + PW, py + ph, 0xEE101018);
        box(ctx, px, py, PW, ph, 0xFF555566);
        ctx.drawTextWithShadow(mc.textRenderer, "Legit PvP - HUD設定", px + 8, py + 6, 0xFFFFFFFF);
        ctx.drawText(mc.textRenderer, "ドラッグで移動 / 行をクリックでON・OFF", px + 8, py + 17, 0xFFAAAAAA, false);

        for (int i = 0; i < els.length; i++) {
            HudConfig.Element e = els[i];
            int ry = py + 30 + i * ROW;
            boolean hover = inside(mouseX, mouseY, px + 4, ry, PW - 8, ROW - 2);
            if (hover) ctx.fill(px + 4, ry, px + PW - 4, ry + ROW - 2, 0x33FFFFFF);
            ctx.drawText(mc.textRenderer, e.label, px + 10, ry + 4, 0xFFFFFFFF, false);
            ctx.fill(px + PW - 46, ry + 1, px + PW - 10, ry + ROW - 3, e.enabled ? 0xFF2E8B3A : 0xFF8B2E2E);
            String t = e.enabled ? "ON" : "OFF";
            ctx.drawText(mc.textRenderer, t, px + PW - 28 - mc.textRenderer.getWidth(t) / 2, ry + 4, 0xFFFFFFFF, false);
        }

        int by = py + ph - 24;
        int bw = (PW - 24) / 2;
        button(ctx, mc, "初期位置に戻す", px + 8, by, bw, mouseX, mouseY);
        button(ctx, mc, "完了", px + 16 + bw, by, bw, mouseX, mouseY);
    }

    private static void button(DrawContext ctx, MinecraftClient mc, String label, int x, int y, int w, int mx, int my) {
        boolean hover = inside(mx, my, x, y, w, 16);
        ctx.fill(x, y, x + w, y + 16, hover ? 0xFF4A4A66 : 0xFF33334A);
        ctx.drawText(mc.textRenderer, label, x + (w - mc.textRenderer.getWidth(label)) / 2, y + 4, 0xFFFFFFFF, false);
    }

    private void onPress(int mx, int my) {
        HudConfig.Element[] els = HudConfig.Element.values();
        int ph = panelH();
        int px = (width - PW) / 2, py = (height - ph) / 2;

        if (inside(mx, my, px, py, PW, ph)) {
            int ry = my - (py + 30);
            if (ry >= 0 && ry < els.length * ROW) {
                HudConfig.Element e = els[ry / ROW];
                e.enabled = !e.enabled;
                HudConfig.save();
                return;
            }
            int by = py + ph - 24;
            int bw = (PW - 24) / 2;
            if (inside(mx, my, px + 8, by, bw, 16)) {
                HudConfig.reset();
            } else if (inside(mx, my, px + 16 + bw, by, bw, 16)) {
                close();
            }
            return;
        }

        for (int i = els.length - 1; i >= 0; i--) {
            HudConfig.Element e = els[i];
            int ex = e.clampX(width), ey = e.clampY(height);
            if (inside(mx, my, ex, ey, e.w, e.h)) {
                dragging = e;
                offX = mx - ex;
                offY = my - ey;
                return;
            }
        }
    }
}
