package com.github.mkram17.bazaarutils.features;

import com.github.mkram17.bazaarutils.events.ChestLoadedEvent;
import com.github.mkram17.bazaarutils.events.SignOpenEvent;
import com.github.mkram17.bazaarutils.utils.GUIUtils;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.github.mkram17.bazaarutils.utils.ScreenInfo;
import com.github.mkram17.bazaarutils.utils.Util;
import lombok.Getter;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;

import static com.github.mkram17.bazaarutils.BazaarUtils.EVENT_BUS;

/**
 * Handles the automated creation of a buy order through the bazaar interface.
 * 
 * Flow:
 * 1. User runs /bu addorder [slot] [amount] [price]
 * 2. Opens bazaar with /bz
 * 3. Clicks inventory slot to select item
 * 4. Clicks "Buy Order" in the item interface
 * 5. Clicks slot 16 to open sign and enters amount
 * 6. Handles price: specific price, "up" for one above, or empty for same as highest
 * 7. Clicks confirm
 */
public class AddOrder {

    private static AddOrder instance;

    public enum State {
        IDLE,
        WAITING_FOR_BAZAAR_MAIN,
        WAITING_FOR_ITEM_PAGE,
        WAITING_FOR_BUY_ORDER_SCREEN,
        WAITING_FOR_AMOUNT_SIGN,
        WAITING_FOR_ORDER_PRICE_SCREEN,
        WAITING_FOR_PRICE_SIGN,
        WAITING_FOR_CONFIRM_SCREEN,
        COMPLETE
    }

    @Getter
    private State state = State.IDLE;
    
    private int inventorySlot;
    private int amount;
    private String price; // null = same as highest, "up" = one above, otherwise a specific price

    // Slot numbers in the bazaar UI (these are hardcoded based on the game UI)
    private static final int BUY_ORDER_SLOT = 15; // "Buy Order" button slot in item page
    private static final int AMOUNT_SIGN_SLOT = 16; // Slot to click to open amount sign
    private static final int SET_PRICE_SLOT = 16; // "Set Price" button slot
    private static final int ONE_ABOVE_SLOT = 12; // "One Above" button slot  
    private static final int SAME_AS_HIGHEST_SLOT = 10; // "Same as Highest" button slot
    private static final int CONFIRM_SLOT = 13; // "Confirm" button slot in confirm screen

    private AddOrder() {
        EVENT_BUS.subscribe(this);
    }

    public static AddOrder getInstance() {
        if (instance == null) {
            instance = new AddOrder();
        }
        return instance;
    }

    /**
     * Starts the add order process.
     * 
     * @param slot The inventory slot (1-36) of the item to order
     * @param amount The amount to order
     * @param price The price: null for same as highest, "up" for one above, or a specific price string
     */
    public void start(int slot, int amount, String price) {
        if (state != State.IDLE) {
            PlayerActionUtil.notifyAll("Add order already in progress. Please wait or restart.");
            return;
        }

        this.inventorySlot = slot;
        this.amount = amount;
        this.price = price;
        this.state = State.WAITING_FOR_BAZAAR_MAIN;

        PlayerActionUtil.notifyAll("Starting add order for slot " + slot + ", amount " + amount + 
                (price == null ? " (same as highest)" : price.equals("up") ? " (one above)" : " at price " + price));

        // Run /bz to open bazaar
        PlayerActionUtil.runCommand("bz");
    }

    public void reset() {
        this.state = State.IDLE;
        this.inventorySlot = 0;
        this.amount = 0;
        this.price = null;
    }

    @EventHandler
    public void onChestLoaded(ChestLoadedEvent event) {
        if (state == State.IDLE || state == State.COMPLETE) {
            return;
        }

        ScreenInfo screenInfo = ScreenInfo.getCurrentScreenInfo();
        String containerName = event.getContainerName();

        switch (state) {
            case WAITING_FOR_BAZAAR_MAIN:
                if (screenInfo.inMenu(ScreenInfo.BazaarMenuType.BAZAAR_MAIN_PAGE)) {
                    handleBazaarMain();
                }
                break;

            case WAITING_FOR_ITEM_PAGE:
                if (screenInfo.inMenu(ScreenInfo.BazaarMenuType.INDIVIDUAL_ITEM)) {
                    handleItemPage();
                }
                break;

            case WAITING_FOR_BUY_ORDER_SCREEN:
                if (screenInfo.inMenu(ScreenInfo.BazaarMenuType.BUY_ORDER)) {
                    handleBuyOrderScreen();
                }
                break;

            case WAITING_FOR_ORDER_PRICE_SCREEN:
                if (screenInfo.inMenu(ScreenInfo.BazaarMenuType.ORDER_PRICE)) {
                    handleOrderPriceScreen();
                }
                break;

            case WAITING_FOR_CONFIRM_SCREEN:
                if (screenInfo.inMenu(ScreenInfo.BazaarMenuType.CONFIRM_BUY_OFFER)) {
                    handleConfirmScreen();
                }
                break;

            default:
                break;
        }
    }

    @EventHandler
    public void onSignOpen(SignOpenEvent event) {
        if (state == State.IDLE || state == State.COMPLETE) {
            return;
        }

        switch (state) {
            case WAITING_FOR_AMOUNT_SIGN:
                handleAmountSign();
                break;

            case WAITING_FOR_PRICE_SIGN:
                handlePriceSign();
                break;

            default:
                break;
        }
    }

    private void handleBazaarMain() {
        // Click the inventory slot to search for that item
        // In bazaar UI, the player's inventory starts after the chest slots
        // The user provides slot 1-36 (1 = first slot of hotbar-style inventory)
        // We calculate: (chest rows * 9) + (slot - 1) to get the actual container slot
        
        state = State.WAITING_FOR_ITEM_PAGE;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof GenericContainerScreen containerScreen)) {
            PlayerActionUtil.notifyAll("Error: Not in container screen");
            reset();
            return;
        }

        GenericContainerScreenHandler handler = containerScreen.getScreenHandler();
        int rows = handler.getRows();
        // Player inventory starts after the chest slots (rows * 9)
        int actualSlot = (rows * 9) + (inventorySlot - 1);
        
        Util.tickExecuteLater(2, () -> {
            GUIUtils.clickSlot(actualSlot, 0);
        });
    }

    private void handleItemPage() {
        // Click "Buy Order" button
        state = State.WAITING_FOR_BUY_ORDER_SCREEN;
        
        // Find the "Buy Order" button by looking for an item with that name
        int buyOrderSlot = findSlotByName("Buy Order");
        if (buyOrderSlot == -1) {
            buyOrderSlot = BUY_ORDER_SLOT; // fallback to hardcoded slot
        }
        
        final int slotToClick = buyOrderSlot;
        Util.tickExecuteLater(2, () -> {
            GUIUtils.clickSlot(slotToClick, 0);
        });
    }

    private void handleBuyOrderScreen() {
        // Click slot 16 to open the sign for entering amount
        state = State.WAITING_FOR_AMOUNT_SIGN;
        
        Util.tickExecuteLater(2, () -> {
            GUIUtils.clickSlot(AMOUNT_SIGN_SLOT, 0);
        });
    }

    private void handleAmountSign() {
        // Enter the amount in the sign and close it
        GUIUtils.setSignText(Integer.toString(amount), true);
        
        // After sign closes, we're back on BUY_ORDER screen and need to handle the price
        // Give some time for the sign to close and screen to update
        Util.tickExecuteLater(5, this::handlePriceSelection);
    }

    private void handlePriceSelection() {
        if (price == null) {
            // Click "Same as highest" (same as top order)
            int sameAsHighestSlot = findSlotByName("Same as Top Order");
            if (sameAsHighestSlot == -1) {
                sameAsHighestSlot = SAME_AS_HIGHEST_SLOT;
            }
            state = State.WAITING_FOR_CONFIRM_SCREEN;
            final int slotToClick = sameAsHighestSlot;
            GUIUtils.clickSlot(slotToClick, 0);
        } else if (price.equalsIgnoreCase("up")) {
            // Click "One above" (top order + 0.1)
            int oneAboveSlot = findSlotByName("Top Order +0.1");
            if (oneAboveSlot == -1) {
                oneAboveSlot = ONE_ABOVE_SLOT;
            }
            state = State.WAITING_FOR_CONFIRM_SCREEN;
            final int slotToClick = oneAboveSlot;
            GUIUtils.clickSlot(slotToClick, 0);
        } else {
            // Click "Set Price" to open the price sign
            // This directly opens a sign, not a new screen
            int setPriceSlot = findSlotByName("Custom Amount");
            if (setPriceSlot == -1) {
                setPriceSlot = SET_PRICE_SLOT;
            }
            state = State.WAITING_FOR_PRICE_SIGN;
            final int slotToClick = setPriceSlot;
            GUIUtils.clickSlot(slotToClick, 0);
        }
    }

    private void handleOrderPriceScreen() {
        // This screen appears when "Set Price" is clicked in some versions
        // Click the sign slot to enter custom price
        state = State.WAITING_FOR_PRICE_SIGN;
        
        // The sign slot in the order price screen is typically slot 16
        Util.tickExecuteLater(2, () -> {
            GUIUtils.clickSlot(AMOUNT_SIGN_SLOT, 0);
        });
    }

    private void handlePriceSign() {
        // Enter the price in the sign
        GUIUtils.setSignText(price, true);
        
        // After sign closes, we should be taken to the confirm screen
        state = State.WAITING_FOR_CONFIRM_SCREEN;
    }

    private void handleConfirmScreen() {
        // Click "Confirm" button
        int confirmSlot = findSlotByName("Confirm");
        if (confirmSlot == -1) {
            confirmSlot = CONFIRM_SLOT;
        }
        
        final int slotToClick = confirmSlot;
        Util.tickExecuteLater(2, () -> {
            GUIUtils.clickSlot(slotToClick, 0);
            state = State.COMPLETE;
            PlayerActionUtil.notifyAll("Buy order placed successfully!");
            reset();
        });
    }

    /**
     * Finds a slot in the current container by searching for an item whose name contains the given text.
     * 
     * @param nameContains The text to search for in item names (case-insensitive)
     * @return The slot index, or -1 if not found
     */
    private int findSlotByName(String nameContains) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof GenericContainerScreen containerScreen)) {
            return -1;
        }

        Inventory inv = containerScreen.getScreenHandler().getInventory();
        String lowerSearch = nameContains.toLowerCase();
        
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            
            String itemName = "";
            if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) {
                itemName = Util.removeFormatting(stack.get(DataComponentTypes.CUSTOM_NAME).getString());
            } else {
                itemName = stack.getName().getString();
            }
            
            if (itemName.toLowerCase().contains(lowerSearch)) {
                return i;
            }
        }
        return -1;
    }
}
