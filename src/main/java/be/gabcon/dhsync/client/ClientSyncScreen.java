package be.gabcon.dhsync.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ClientSyncScreen extends Screen {
    private final Screen parent;
    private volatile String stage = "Starting";
    private volatile String detail = "";
    private volatile long current;
    private volatile long total;
    private volatile String failure;

    public ClientSyncScreen(Screen parent) {
        super(Component.literal("GabCon DH Sync"));
        this.parent = parent;
    }

    public void progress(String stage, String detail, long current, long total) {
        this.stage = stage == null ? "" : stage;
        this.detail = detail == null ? "" : detail;
        this.current = current;
        this.total = total;
    }

    public void fail(String message) {
        this.failure = message == null ? "Unknown synchronization error" : message;
        if (this.minecraft != null) {
            this.minecraft.execute(this::rebuildWidgets);
        }
    }

    @Override
    protected void init() {
        if (failure != null) {
            addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
                if (minecraft != null) minecraft.setScreen(parent);
            }).bounds(width / 2 - 100, height / 2 + 55, 200, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do not call Screen#renderBackground here. On some client/render configurations
        // Minecraft's menu blur can also soften this transient screen's foreground.
        // A simple translucent veil keeps the previous screen recognizable while all
        // GabCon status text and progress indicators remain pixel-sharp.
        graphics.fill(0, 0, width, height, 0xCC000000);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, height / 2 - 55, 0xFFFFFF);

        if (failure != null) {
            graphics.drawCenteredString(font, Component.literal("Synchronization failed"), width / 2, height / 2 - 20, 0xFF5555);
            graphics.drawCenteredString(font, Component.literal(trim(failure, 90)), width / 2, height / 2, 0xFFFFFF);
        } else {
            graphics.drawCenteredString(font, Component.literal(stage), width / 2, height / 2 - 20, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.literal(trim(detail, 90)), width / 2, height / 2, 0xCFCFCF);

            int barWidth = Math.min(320, Math.max(160, width - 80));
            int barHeight = 8;
            int barX = width / 2 - barWidth / 2;
            int barY = height / 2 + 18;
            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF303030);

            if (total > 0) {
                double pct = Math.min(100.0, Math.max(0.0, current * 100.0 / total));
                int filled = (int) Math.round(barWidth * pct / 100.0);
                if (filled > 0) {
                    graphics.fill(barX, barY, barX + filled, barY + barHeight, 0xFF55FF55);
                }
                graphics.drawCenteredString(font, Component.literal(String.format(java.util.Locale.ROOT, "%.1f%%", pct)),
                        width / 2, barY + 13, 0xFFFFFF);
            } else {
                graphics.drawCenteredString(font, Component.literal("Working..."),
                        width / 2, barY + 13, 0xAAAAAA);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return failure != null;
    }

    @Override
    public void onClose() {
        if (failure != null && minecraft != null) minecraft.setScreen(parent);
    }

    private static String trim(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
