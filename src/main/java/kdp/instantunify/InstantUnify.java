package kdp.instantunify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.IContainerListener;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(InstantUnify.MOD_ID)
public class InstantUnify {

    public static final String MOD_ID = "instantunify";
    public static final Logger LOG = LogManager.getLogger(InstantUnify.class);

    private ForgeConfigSpec.ConfigValue<List<? extends String>> blacklist, whitelist, preferredMods, blacklistedMods;
    private ForgeConfigSpec.BooleanValue drop, harvest, gui, second, death, change;
    private ForgeConfigSpec.EnumValue<ListMode> listMode;
    private ForgeConfigSpec.ConfigValue<List<? extends List<? extends String>>> alts;
    private Map<String, List<String>> alternatives;

    private Cache<Item, ResourceLocation[]> tagNameCache = CacheBuilder.newBuilder()
            .expireAfterWrite(20, TimeUnit.SECONDS).build();

    public InstantUnify() {
        Pair<Object, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(b -> {
            blacklist = b.comment("Tag names that shouldn't be unified (supports regex)")
                    .defineList("blacklist", Arrays.asList("minecraft:.+", "forge:glass.+"), s -> s instanceof String);
            whitelist = b.comment("Tag names that should be unified (supports regex)").defineList("whitelist",
                    Arrays.asList("forge:ores\\/.+", "forge:ingots\\/.+", "forge:nuggets\\/.+",
                            "forge:storage_blocks\\/.+", "forge:gems\\/.+", "forge:dusts\\/.+", "forge:gears\\/.+",
                            "forge:plates\\/.+", "forge:rods\\/.+"), s -> s instanceof String);
            listMode = b.defineEnum("listMode", ListMode.USE_BOTH_LISTS);
            preferredMods = b.comment("Preferred Mods").defineList("preferredMods",
                    Arrays.asList("minecraft", "thermalfoundation", "immersiveengineering", "embers"),
                    s -> s instanceof String);
            blacklistedMods = b.comment("Blacklisted Mods")
                    .defineList("blacklistMods", Arrays.asList("chisel", "astralsorcery"), s -> s instanceof String);
            alts = b.comment("Tag names that should be unified even if they are different")
                    .defineList("alternatives", Arrays.asList(Arrays.asList("aluminum", "aluminium", "bauxite")),//
                            o -> o instanceof List && (((List) o).isEmpty() || ((List) o).get(0) instanceof String));
            b.push("Unify event");
            drop = b.comment("Unify when items drop").define("drop", true);
            harvest = b.comment("Unify when blocks are harvested").define("harvest", true);
            death = b.comment("Unify drops when entities die").define("death", true);
            second = b.comment("Unify every second items in player's inventory").define("second", false);
            //gui = b.comment("Unify items in player's inventory when GUI is opened/closed").define("gui", false);
            change = b.comment("Unify items in player's inventory when they change").define("change", true);
            b.pop();
            return null;
        });
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, pair.getValue());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void spawn(EntityJoinWorldEvent event) {
        if (drop.get() && event.getEntity() instanceof ItemEntity && !event.getWorld().isRemote) {
            replace(((ItemEntity) event.getEntity()).getItem())
                    .ifPresent(s -> ((ItemEntity) event.getEntity()).setItem(s));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void harvest(BlockEvent.HarvestDropsEvent event) {
        if (harvest.get()) {
            event.getDrops().replaceAll(s -> replace(s).orElse(s));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void death(LivingDropsEvent event) {
        if (death.get()) {
            event.getDrops().forEach(e -> replace(e.getItem()).ifPresent(e::setItem));
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if (second.get() && event.phase == TickEvent.Phase.END && !event.player.world.isRemote && event.player.world
                .getGameTime() % 20 == 18) {
            PlayerEntity player = event.player;
            if (replaceInventory(player) && player.openContainer != null) {
                player.openContainer.detectAndSendChanges();
            }
        }
    }

    private boolean replaceInventory(PlayerEntity player) {
        boolean changed = false;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack slot = player.inventory.getStackInSlot(i);
            Optional<ItemStack> op = replace(slot);
            if (op.isPresent()) {
                player.inventory.setInventorySlotContents(i, op.get());
                changed = true;
            }
        }
        return changed;
    }

    //@SubscribeEvent
    public void open(PlayerContainerEvent event) {
        if (gui.get() && !event.getPlayer().world.isRemote) {
            PlayerEntity player = event.getPlayer();
            if (replaceInventory(player) && player.openContainer != null) {
                player.openContainer.detectAndSendChanges();
            }
        }
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity && event
                .getPlayer().openContainer instanceof PlayerContainer) {
            event.getPlayer().openContainer.addListener(new IContainerListener() {
                @Override
                public void sendAllContents(Container containerToSend, NonNullList<ItemStack> itemsList) {
                }

                @Override
                public void sendSlotContents(Container containerToSend, int slotInd, ItemStack stack) {
                    if (change.get()) {
                        replace(containerToSend.inventorySlots.get(slotInd).getStack())//
                                .ifPresent(s -> containerToSend.inventorySlots.get(slotInd).putStack(s));
                    }
                }

                @Override
                public void sendWindowProperty(Container containerIn, int varToUpdate, int newValue) {
                }
            });
        }
    }

    private Optional<ItemStack> replace(ItemStack orig) {
        if (orig.isEmpty() || (orig.getTag() != null && !orig.getTag().isEmpty())//
                || blacklistedMods.get().contains(orig.getItem().getRegistryName().getNamespace())) {
            return Optional.empty();
        }
        final ResourceLocation[] tagNames = tagNames(orig);
        if (tagNames.length == 0) {
            return Optional.empty();
        }
        List<List<ItemStack>> itemLists = Arrays.stream(tagNames)//
                .map(rl -> ItemTags.getCollection().get(rl))//
                .filter(Objects::nonNull)//
                .map(tag -> tag.getAllElements().stream()//
                        .sorted((i1, i2) -> {
                            int index1 = preferredMods.get().indexOf(i1.getRegistryName().getNamespace()),//
                                    index2 = preferredMods.get().indexOf(i2.getRegistryName().getNamespace());
                            return Integer.compare(index1 == -1 ? 999 : index1, index2 == -1 ? 999 : index2);
                        })//
                        .map(i -> new ItemStack(i, orig.getCount()))//
                        .collect(Collectors.toList()))//
                .filter(l -> !l.isEmpty())//
                .collect(Collectors.toList());
        for (List<ItemStack> items : itemLists) {
            for (ItemStack item : items) {
                if (Arrays.equals(tagNames, tagNames(item))) {
                    return Optional.of(item);
                }
            }
        }
        return Optional.empty();
    }

    private ResourceLocation[] tagNames(ItemStack s) {
        try {
            return tagNameCache.get(s.getItem(), () -> {
                Set<ResourceLocation> unmodifiableNames = s.getItem().getTags();
                Set<ResourceLocation> names = new HashSet<>(unmodifiableNames);
                if (alternatives == null) {
                    alternatives = new HashMap<>();
                    for (List<? extends String> lis : alts.get()) {
                        for (String n : lis) {
                            List<String> copy = new ArrayList<>(lis);
                            Validate.isTrue(!n.contains(":"), ": is not allowed in alternative tag");
                            copy.remove(n);
                            if (!copy.isEmpty())
                                alternatives.put(n, copy);
                        }
                    }
                }
                for (ResourceLocation name : unmodifiableNames) {
                    for (Map.Entry<String, List<String>> e : alternatives.entrySet()) {
                        String key = e.getKey();
                        if (name.getPath().contains(key)) {
                            List<String> val = e.getValue();
                            for (String alt : val) {
                                names.add(new ResourceLocation(name.getNamespace(), name.getPath().replace(key, alt)));
                            }
                        }
                    }
                }
                return names.stream().filter(r ->//
                        ((listMode.get() != ListMode.USE_WHITELIST//
                                && listMode.get() != ListMode.USE_BOTH_LISTS)//
                                || whitelist.get().stream().anyMatch(ss -> Pattern.matches(ss, r.toString())))//
                                //###############################################
                                && ((listMode.get() != ListMode.USE_BLACKLIST//
                                && listMode.get() != ListMode.USE_BOTH_LISTS)//
                                || blacklist.get().stream().noneMatch(ss -> Pattern.matches(ss, r.toString()))))
                        .sorted().toArray(ResourceLocation[]::new);
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private enum ListMode {
        USE_WHITELIST, USE_BLACKLIST, USE_BOTH_LISTS, USE_NO_LIST;
    }

}
