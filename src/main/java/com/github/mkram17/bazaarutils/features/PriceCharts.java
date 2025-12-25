package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.BazaarUtils;
import com.github.mkram17.bazaarutils.config.BUConfigGui;
import com.github.mkram17.bazaarutils.data.BazaarData;
import com.github.mkram17.bazaarutils.events.SlotClickEvent;
import com.github.mkram17.bazaarutils.events.handlers.BUListener;
import com.github.mkram17.bazaarutils.misc.orderinfo.OrderInfoContainer;
import com.github.mkram17.bazaarutils.utils.ScreenInfo;
import com.github.mkram17.bazaarutils.utils.Util;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import lombok.Getter;
import lombok.Setter;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PriceCharts implements ItemTooltipCallback, BUListener {
    @Getter @Setter
    private boolean showOutsideBazaar = false;

    // Cache: sanitized item name -> should show tooltip
    private static final Map<String, Boolean> SHOW_CACHE = new ConcurrentHashMap<>();

    @Override
    public void getTooltip(ItemStack stack, Item.TooltipContext ctx, TooltipType type, List<Text> lines) {
        if (stack == null || stack.isEmpty() || !shouldShow()) return;
        if (stack.getItem().getName().getString().contains("GLASS_PANE")) return;

        String key = sanitizeName(stack.getName().getString());

        // Lazily populate cache if a synced/replaced stack appears later
        if (!SHOW_CACHE.computeIfAbsent(key, OrderInfoContainer::isValidName))
            return;

        MutableText text = Text.literal("CTRL+SHIFT click for price charts & other info")
                .formatted(Formatting.GOLD, Formatting.BOLD);
        MutableText poweredBy = Text.literal("Powered by skyblock.finance")
                .formatted(Formatting.GRAY);

        lines.add(Text.literal(""));
        lines.add(text);
        lines.add(poweredBy);
    }

    @EventHandler
    private void onClick(SlotClickEvent e){
        if (!shouldShow() || e.isCancelled())
            return;
        //? if > 1.21.8{
        if (!MinecraftClient.getInstance().isShiftPressed() || !MinecraftClient.getInstance().isCtrlPressed())
            return;
        //?} else {
        /*if (!(Screen.hasShiftDown() && Screen.hasControlDown()))
            return;
        *///?}
        String itemName = sanitizeName(e.slot.getStack().getName().getString());
        if (!SHOW_CACHE.getOrDefault(itemName, false)) return;

        String productID = BazaarData.findProductIdOptional(itemName).get(); // All cached items are safe
        String link = "https://skyblock.finance/items/" + productID;
        MinecraftClient.getInstance().setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                try {
                    net.minecraft.util.Util.getOperatingSystem().open(new URI(link));
                } catch (URISyntaxException ex) {
                    Util.notifyError("Failed to open skyblock.finance link.", ex);
                }
            }
            MinecraftClient.getInstance().setScreen(null);
        }, link, true));
        e.cancel();
    }

    @Override
    public void subscribe() {
        ItemTooltipCallback.EVENT.register(this);
        BazaarUtils.EVENT_BUS.subscribe(this);
        // Clear cache when (re)subscribing to avoid stale bazaar state leakage
        SHOW_CACHE.clear();
    }

    private boolean shouldShow(){
        ScreenInfo screenInfo = ScreenInfo.getCurrentScreenInfo();
        return (screenInfo.inBazaar() || showOutsideBazaar) && !screenInfo.inMenu(ScreenInfo.BazaarMenuType.BAZAAR_MAIN_PAGE);
    }

    private static String sanitizeName(String raw){
        int len = raw.length();
        if (len > 3 && raw.charAt(len - 2) == 'x' && Character.isDigit(raw.charAt(len - 1))) {
            int idx = raw.lastIndexOf('x');
            if (idx > 0) return raw.substring(0, idx - 1);
        }
        return raw;
    }

    public Option<Boolean> createOption() {
        return Option.<Boolean>createBuilder()
                .name(Text.literal("Show Price Charts Outside Bazaar"))
                .description(OptionDescription.of(Text.literal("Usually the option to CTRL+SHIFT click an item to see the price charts and other information on skyblock.finance is only shown inside the Bazaar while in an item view. This enables the feature outside of the Bazaar as well.")))
                .binding(false,
                        this::isShowOutsideBazaar,
                        this::setShowOutsideBazaar)
                .controller(BUConfigGui::createBooleanController)
                .build();
    }
}